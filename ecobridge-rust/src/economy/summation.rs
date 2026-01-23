// ==================================================
// FILE: ecobridge-rust/src/economy/summation.rs
// ==================================================

//! Effective Volume Summation Module (v1.6.0 SIMD + Precision Hardened)
//! 
//! 本模块负责交易物品数量（Quantity）的聚合计算。
//! 
//! 变更记录:
//! - [v1.6.0] 适配 i64 Micros 定点数协议，消除浮点累积误差。
//! - [v1.1] 优化: 使用二分查找降至 O(logN + M)。

use crate::models::HistoryRecord;
use crate::storage;
use std::sync::RwLock;
use lazy_static::lazy_static;

#[cfg(target_arch = "x86_64")]
use std::arch::x86_64::*;

#[cfg(feature = "parallel")]
use rayon::prelude::*;

// ==================== 工业级常量定义 ====================

const PARALLEL_THRESHOLD: usize = 750;
const MS_PER_DAY: f64 = 86_400_000.0;
const MAX_FUTURE_TOLERANCE: i64 = 60_000;
const MICROS_SCALE: f64 = 1_000_000.0; // [v1.6.0] 精度缩放因子

// 内存管理阈值
const MAX_HISTORY_SIZE: usize = 500_000;
const PRUNE_TO_SIZE: usize = 400_000;

// ==================== 全局内存态 (Hot Memory Layer) ====================

lazy_static! {
    static ref HOT_HISTORY: RwLock<Vec<HistoryRecord>> = RwLock::new(Vec::with_capacity(MAX_HISTORY_SIZE));
}

/// 初始化加载逻辑 (服务器启动时调用)
pub fn hydrate_hot_store() {
    let records = storage::load_recent_history(30); 
    let len = records.len();
    
    let mut lock = HOT_HISTORY.write().unwrap();
    *lock = records;
    
    println!("[EcoBridge-Native] v1.6.0 SIMD 引擎热数据装填完成: {} 条记录", len);
}

/// 实时双写逻辑
/// @param amount 这里的 amount 为原始 double，内部转换为 i64 Micros 存储
pub fn append_trade_to_memory(ts: i64, amount: f64) {
    let mut lock = HOT_HISTORY.write().unwrap();
    
    lock.push(HistoryRecord {
        timestamp: ts,
        amount_micros: (amount * MICROS_SCALE) as i64, // [Precision Fix] 转换为定点数
    });
    
    if lock.len() > MAX_HISTORY_SIZE {
        let remove_count = lock.len() - PRUNE_TO_SIZE;
        lock.drain(0..remove_count);
    }
}

// ==================== 核心接口 ====================

pub fn query_neff_internal(
    current_ts: i64,
    tau: f64,
) -> f64 {
    let lock = HOT_HISTORY.read().unwrap();
    calculate_volume_in_memory(&lock, current_ts, tau)
}

// ==================== 内存计算实现 (Binary Search + SIMD) ====================

pub fn calculate_volume_in_memory(
    history: &[HistoryRecord],
    current_time: i64,
    tau: f64,
) -> f64 {
    if history.is_empty() || tau <= 0.0 {
        return 0.0;
    }

    let valid_future_limit = current_time + MAX_FUTURE_TOLERANCE;
    let valid_past_limit = current_time - (tau * MS_PER_DAY * 10.0) as i64;

    let start_idx = history.partition_point(|r| r.timestamp < valid_past_limit);
    let relevant_slice = &history[start_idx..];

    if relevant_slice.is_empty() {
        return 0.0;
    }

    let t_min = relevant_slice[0].timestamp;
    let lambda = 1.0 / (tau * MS_PER_DAY);
    let base_multiplier = (-(current_time - t_min) as f64 * lambda).exp();

    #[cfg(target_arch = "x86_64")]
    if is_x86_feature_detected!("avx2") {
        let sum_partial = unsafe { 
            compute_partial_simd(relevant_slice, t_min, lambda, valid_future_limit, valid_past_limit) 
        };
        // 最终求和时缩放回标准单位
        let result = (sum_partial / MICROS_SCALE) * base_multiplier;
        return if result.is_finite() { result } else { 0.0 };
    }

    // Fallback: 标量实现 (针对 slice)
    let compute_partial = |rec: &HistoryRecord| -> f64 {
        if rec.timestamp > valid_future_limit {
            return 0.0; 
        }
        let dt_rel = rec.timestamp.saturating_sub(t_min) as f64;
        (rec.amount_micros as f64) * (dt_rel * lambda).exp()
    };

    let sum_partial: f64 = if relevant_slice.len() >= PARALLEL_THRESHOLD {
        #[cfg(feature = "parallel")]
        { relevant_slice.par_iter().map(compute_partial).sum() }
        #[cfg(not(feature = "parallel"))]
        { relevant_slice.iter().map(compute_partial).sum() }
    } else {
        relevant_slice.iter().map(compute_partial).sum()
    };

    let result = (sum_partial / MICROS_SCALE) * base_multiplier;
    if result.is_finite() { result } else { 0.0 }
}

/// AVX2 优化的部分和计算
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn compute_partial_simd(
    history: &[HistoryRecord], 
    t_min: i64, 
    lambda: f64,
    valid_future: i64,
    valid_past: i64
) -> f64 {
    let mut sum_vec = _mm256_setzero_pd();
    
    let v_tmin = _mm256_set1_pd(t_min as f64);
    let v_lambda = _mm256_set1_pd(lambda);

    let chunks = history.chunks_exact(4);
    let remainder = chunks.remainder();

    for chunk in chunks {
        let t0 = chunk[0].timestamp;
        let t3 = chunk[3].timestamp;
        
        if t3 > valid_future || t0 < valid_past {
            for r in chunk {
                if r.timestamp <= valid_future && r.timestamp >= valid_past {
                     let dt = (r.timestamp - t_min) as f64;
                     let val = (r.amount_micros as f64) * (dt * lambda).exp();
                     let v_val = _mm256_set_pd(0.0, 0.0, 0.0, val);
                     sum_vec = _mm256_add_pd(sum_vec, v_val);
                }
            }
            continue;
        }

        // 核心向量化计算路径
        let v_ts = _mm256_set_pd(
            chunk[3].timestamp as f64,
            chunk[2].timestamp as f64,
            chunk[1].timestamp as f64,
            chunk[0].timestamp as f64,
        );
        let v_amount = _mm256_set_pd(
            chunk[3].amount_micros as f64, // [Fix] 访问 amount_micros
            chunk[2].amount_micros as f64,
            chunk[1].amount_micros as f64,
            chunk[0].amount_micros as f64,
        );

        let v_dt = _mm256_sub_pd(v_ts, v_tmin);
        let v_exponent = _mm256_mul_pd(v_dt, v_lambda);

        let mut arr = [0.0f64; 4];
        _mm256_storeu_pd(arr.as_mut_ptr(), v_exponent);
        arr[0] = arr[0].exp();
        arr[1] = arr[1].exp();
        arr[2] = arr[2].exp();
        arr[3] = arr[3].exp();
        let v_exp = _mm256_loadu_pd(arr.as_ptr());

        let v_partial = _mm256_mul_pd(v_amount, v_exp);
        sum_vec = _mm256_add_pd(sum_vec, v_partial);
    }

    let mut temp = [0.0f64; 4];
    _mm256_storeu_pd(temp.as_mut_ptr(), sum_vec);
    let mut total = temp[0] + temp[1] + temp[2] + temp[3];

    for rec in remainder {
        if rec.timestamp <= valid_future && rec.timestamp >= valid_past {
            let dt = (rec.timestamp - t_min) as f64;
            total += (rec.amount_micros as f64) * (dt * lambda).exp();
        }
    }

    total
}