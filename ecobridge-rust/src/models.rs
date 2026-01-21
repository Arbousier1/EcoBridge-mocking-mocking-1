// ==================================================
// FILE: ecobridge-rust/src/models.rs
// ==================================================

//! EcoBridge Economy System - Integrated Data Models (SSoT v1.6.0 - Precision Hardened)
//! 
//! # 核心重构准则
//! 1. **i64 Micros**: 所有金额、余额、硬限额均改为 `c_longlong` (i64)，单位为 10^-6。
//! 2. **保持偏移**: 由于 i64 和 f64 均为 8 字节，现有 FFM VarHandle 偏移量无需修改。
//! 3. **系数保留**: 所有的倍率、增长率、系数（如 lambda, inflation_rate）保留为 `c_double`。

use libc::{c_double, c_int, c_longlong};

// ==================== 1. 物理控制器状态 (State) ====================

/// 工业级 PID 控制器状态 (72 bytes)
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct PidState {
    pub kp: c_double,                // Offset 0
    pub ki: c_double,                // Offset 8
    pub kd: c_double,                // Offset 16
    pub lambda: c_double,            // Offset 24
    pub integral: c_double,          // Offset 32: 误差积分项
    pub prev_pv: c_double,           // Offset 40: 上一次观测值
    pub filtered_d: c_double,        // Offset 48
    pub integration_limit: c_double, // Offset 56
    pub is_saturated: c_int,         // Offset 64
    pub _padding: c_int,             // Offset 68
}

impl Default for PidState {
    fn default() -> Self {
        Self {
            kp: 0.5, ki: 0.1, kd: 0.05, lambda: 0.01,
            integral: 0.0, prev_pv: 0.0, filtered_d: 0.0,
            integration_limit: 30.0, is_saturated: 0,
            _padding: 0,
        }
    }
}

// ==================== 2. 交易记录模型 (Records) ====================

/// 单条历史交易快照 (16 bytes)
#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct HistoryRecord {
    pub timestamp: c_longlong,      // Offset 0
    pub amount_micros: c_longlong,  // Offset 8: [Precision] 交易额 Micros
}

// ==================== 3. 业务演算上下文 (Contexts) ====================

/// 交易定价演算上下文 (64 bytes)
#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct TradeContext {
    pub base_price_micros: c_longlong, // 0: [Precision] 基础价格 Micros
    pub current_amount: c_longlong,    // 8: [Precision] 当前数量 Micros
    pub inflation_rate: c_double,      // 16: 通胀倍率 (系数)
    pub current_timestamp: c_longlong, // 24
    pub play_time_seconds: c_longlong, // 32
    pub timezone_offset: c_int,        // 40
    pub newbie_mask: c_int,            // 44
    pub market_heat: c_double,         // 48: 市场热度 (导数/流速)
    pub eco_saturation: c_double,      // 56: 饱和度 (系数)
}

/// 交易审计上下文 (96 bytes)
#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct TransferContext {
    pub amount_micros: c_longlong,     // 0: [Precision] 交易金额 Micros
    pub sender_balance: c_longlong,    // 8: [Precision] 发送者余额 Micros
    pub receiver_balance: c_longlong,  // 16: [Precision] 接收者余额 Micros
    pub inflation_rate: c_double,      // 24
    
    pub item_base_limit: c_longlong,   // 32: [Precision] 基础限额 Micros
    pub item_growth_rate: c_double,    // 40: 增长系数 (平方根增长率)
    pub item_max_limit: c_longlong,    // 48: [Precision] 硬上限 Micros
    
    pub sender_play_time: c_longlong,  // 56
    pub receiver_play_time: c_longlong, // 64
    pub sender_activity_score: c_double, // 72
    pub sender_velocity: c_double,     // 80
    pub _padding: c_longlong,          // 88
}

// ==================== 4. 环境配置模型 (Configs) ====================

/// 市场动态定价配置 (72 bytes)
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct MarketConfig {
    pub base_lambda: c_double,           // 0
    pub volatility_factor: c_double,     // 8
    pub seasonal_amplitude: c_double,    // 16
    pub weekend_multiplier: c_double,    // 24
    pub newbie_protection_rate: c_double, // 32
    pub seasonal_weight: c_double,       // 40
    pub weekend_weight: c_double,        // 48
    pub newbie_weight: c_double,         // 56
    pub inflation_weight: c_double,      // 64
}

impl Default for MarketConfig {
    fn default() -> Self {
        Self {
            base_lambda: 0.1, volatility_factor: 1.0,
            seasonal_amplitude: 0.15, weekend_multiplier: 1.2,
            newbie_protection_rate: 0.2,
            seasonal_weight: 0.25, weekend_weight: 0.25,
            newbie_weight: 0.25, inflation_weight: 0.25,
        }
    }
}

/// 审计监管与计税配置 (96 bytes)
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct RegulatorConfig {
    pub base_tax_rate: c_double,       // 0
    pub luxury_threshold: c_longlong,  // 8: [Precision] 奢侈税阈值 Micros
    pub luxury_tax_rate: c_double,     // 16
    pub wealth_gap_tax_rate: c_double, // 24
    pub poor_threshold: c_longlong,    // 32: [Precision] 贫困判定线 Micros
    pub rich_threshold: c_longlong,    // 40: [Precision] 富裕判定线 Micros
    pub _reserved: c_double,           // 48
    pub warning_ratio: c_double,       // 56
    pub warning_min_amount: c_longlong, // 64: [Precision] 触发警报金额 Micros
    pub newbie_hours: c_double,        // 72
    pub veteran_hours: c_double,       // 80
    pub velocity_threshold: c_double,  // 88
}

impl Default for RegulatorConfig {
    fn default() -> Self {
        Self {
            base_tax_rate: 0.05, 
            luxury_threshold: 100_000_000_000, // 100k
            luxury_tax_rate: 0.10, wealth_gap_tax_rate: 0.20,
            poor_threshold: 10_000_000_000,    // 10k
            rich_threshold: 1_000_000_000_000, // 1M
            _reserved: 0.0,
            warning_ratio: 0.9,
            warning_min_amount: 50_000_000_000,
            newbie_hours: 10.0, veteran_hours: 100.0,
            velocity_threshold: 20.0,
        }
    }
}

// ==================== 5. 演算结果集 (Results) ====================

/// 交易演算最终结果 (16 bytes)
#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct TransferResult {
    pub final_tax_micros: c_longlong, // 0: [Precision] 计算得出的税费 Micros
    pub is_blocked: c_int,           // 8: 0=通过, 1=拒绝
    pub warning_code: c_int,         // 12
}

// ==================== 6. 静态布局一致性测试 ====================

#[cfg(test)]
mod tests {
    use super::*;
    use std::mem;

    #[test]
    fn verify_precision_alignment() {
        // 验证结构体总大小 (必须与 Java 侧配置绝对一致)
        assert_eq!(mem::size_of::<PidState>(), 72);
        assert_eq!(mem::size_of::<TradeContext>(), 64);
        assert_eq!(mem::size_of::<TransferContext>(), 96);
        assert_eq!(mem::size_of::<MarketConfig>(), 72); 
        assert_eq!(mem::size_of::<RegulatorConfig>(), 96);
        assert_eq!(mem::size_of::<TransferResult>(), 16);
        
        // 验证关键金额字段的偏移
        assert_eq!(mem::offset_of!(TransferContext, sender_balance), 8);
        assert_eq!(mem::offset_of!(RegulatorConfig, rich_threshold), 40);
        assert_eq!(mem::offset_of!(TransferResult, final_tax_micros), 0);
    }
}