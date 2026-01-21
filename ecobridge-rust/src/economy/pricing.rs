// ==================================================
// FILE: ecobridge-rust/src/economy/pricing.rs
// ==================================================

use crate::models::{TradeContext, MarketConfig};
use rayon::prelude::*;
use crate::economy::environment;

/// 精度缩放常量 (1.0 = 1,000,000 Micros)
const MICROS_SCALE: f64 = 1_000_000.0;

// -----------------------------------------------------------------------------
// 1. 内部定价核心逻辑 (Core Engine)
// -----------------------------------------------------------------------------

/// 具备行为经济学感知的定价引擎 (v1.6.0 Precision Hardened)
/// 
/// @param base_price_micros 物品基础定价 (i64 Micros)
/// @param n_eff 有效物品供应累积量 (来自 SIMD 演算，已缩放为标准 f64)
/// @param trade_amount_micros 本次交易的物品件数 (i64 Micros)：正数为卖出，负数为买入
fn compute_price_behavioral_core(
    base_price_micros: i64,
    n_eff: f64,
    trade_amount_micros: i64, 
    lambda: f64,
    epsilon: f64,
) -> f64 {
    // 1. 数据转换与安全性检查
    let base_price_f64 = (base_price_micros as f64) / MICROS_SCALE;
    let trade_amount_f64 = (trade_amount_micros as f64) / MICROS_SCALE;

    if !base_price_f64.is_finite() || !n_eff.is_finite() || 
       !lambda.is_finite() || !epsilon.is_finite() {
        return 0.01;
    }

    // 2. 非对称灵敏度 (Asymmetric Sensitivity)
    // 逻辑：卖出物品时灵敏度降低(0.6x)，模拟“价格下行粘性”
    let adj_lambda = if trade_amount_micros > 0 {
        lambda * 0.6 
    } else {
        lambda
    };

    // 3. 计算总有效供应量冲击
    let total_n = n_eff + trade_amount_f64;

    // 4. 指数演算与平滑限幅 (Soft Clamping)
    let raw_exponent = (-adj_lambda * total_n).clamp(-100.0, 100.0);
    
    // 使用 tanh 确保价格曲线在极端工业产出下平滑逼近底价，不会突变为 0
    let clamped_exponent = 10.0 * (raw_exponent / 10.0).tanh();
    
    let final_price = base_price_f64 * epsilon * clamped_exponent.exp();
    
    // 5. 绝对硬底线 (0.01 货币单位)
    final_price.max(0.01)
}

// -----------------------------------------------------------------------------
// 2. 阶梯定价与底价保护 (Defense Layers)
// -----------------------------------------------------------------------------

/// 计算阶梯定价 (Tier Pricing)
/// 针对单笔超大量物品售出的防御性降价逻辑
pub fn compute_tier_price_internal(
    base_price: f64, 
    quantity_f64: f64, 
    is_sell: bool
) -> f64 {
    // 只有卖出且物品数量超过 500 件时触发阶梯折扣
    if !is_sell || quantity_f64 <= 500.0 || quantity_f64 <= 0.0 {
        return base_price;
    }

    let mut total_value = 0.0;
    let mut remaining = quantity_f64;

    // Tier 1: 0 - 500 件 (100% 原始演算价)
    let t1 = remaining.min(500.0);
    total_value += t1 * base_price;
    remaining -= t1;

    // Tier 2: 501 - 2000 件 (85% 折扣价)
    if remaining > 0.0 {
        let t2 = remaining.min(1500.0);
        total_value += t2 * (base_price * 0.85);
        remaining -= t2;
    }

    // Tier 3: 2000 件以上 (60% 深度折扣)
    if remaining > 0.0 {
        total_value += remaining * (base_price * 0.60);
    }

    total_value / quantity_f64
}

/// 包含动态底价保护的最终价格演算
/// @param hist_avg 物品历史均价 (标准 f64)，用于计算动态地板价
pub fn compute_price_bounded_internal(
    base_micros: i64, n_eff: f64, amt_micros: i64, lambda: f64, eps: f64, 
    hist_avg: f64
) -> f64 {
    let raw_price = compute_price_behavioral_core(base_micros, n_eff, amt_micros, lambda, eps);
    
    // 动态地板价逻辑: 价格不得低于历史均价的 20%，防止市场彻底崩溃
    let floor = (hist_avg * 0.2).max(0.01);
    
    if raw_price < floor {
        floor
    } else {
        raw_price
    }
}

// -----------------------------------------------------------------------------
// 3. 转发逻辑层 (API Layer)
// -----------------------------------------------------------------------------

/// 获取单体实时价格 (不包含本次交易的预测冲击)
pub fn compute_price_final_internal(base_micros: i64, n_eff: f64, lambda: f64, eps: f64) -> f64 {
    compute_price_behavioral_core(base_micros, n_eff, 0, lambda, eps)
}

/// 获取单体成交价格 (包含本次物品数量冲击)
pub fn compute_price_humane_internal(base_micros: i64, n_eff: f64, amt_micros: i64, lambda: f64, eps: f64) -> f64 {
    compute_price_behavioral_core(base_micros, n_eff, amt_micros, lambda, eps)
}

/// 批量价格演算内核 - 适配 v1.6.0 高精度上下文
pub unsafe fn compute_batch_prices_internal(
    count: usize,
    neff: f64,
    ctx_ptr: *const TradeContext,
    cfg_ptr: *const MarketConfig,
    hist_avgs_ptr: *const f64,
    lambdas_ptr: *const f64,
    output_ptr: *mut f64,
) {
    let ctx_slice = std::slice::from_raw_parts(ctx_ptr, count);
    let cfg_slice = std::slice::from_raw_parts(cfg_ptr, count);
    let hist_avgs = std::slice::from_raw_parts(hist_avgs_ptr, count);
    let lambdas = std::slice::from_raw_parts(lambdas_ptr, count);
    let output = std::slice::from_raw_parts_mut(output_ptr, count);

    // 并行演算，确保在打开商店大菜单时零延迟
    output.par_iter_mut()
        .enumerate()
        .for_each(|(i, price_out)| {
            let ctx = &ctx_slice[i];
            let cfg = &cfg_slice[i];
            let lambda = lambdas[i];
            let hist_avg = hist_avgs[i];

            let epsilon = environment::calculate_epsilon_internal(ctx, cfg);

            *price_out = compute_price_bounded_internal(
                ctx.base_price_micros, // 使用适配后的字段名
                neff, 
                0, 
                lambda, 
                epsilon, 
                hist_avg
            );
        });
}