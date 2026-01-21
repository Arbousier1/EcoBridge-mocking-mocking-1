// ==================================================
// FILE: ecobridge-rust/src/economy/macro_eco.rs
// ==================================================

//! Macro Economy Mathematics Module (v1.6.0 - Precision Aligned)
//! 
//! 本模块负责宏观经济指标的纯数学计算。
//! 
//! # 精度协议说明
//! 虽然本模块内部使用 `f64` 进行非线性运算，但其输入参数（如 `m1_supply`）
//! 必须是经过标准化处理的（即：原始金额 / 1,000,000.0）。
//! 这种“中间高精度浮点，两端定点整数”的架构确保了宏观趋势计算的平滑性。

/// 计算通货膨胀率 (Inflation Rate)
/// 
/// 公式: ε = (当前流通热度 / M1 货币总量)
/// 约束: 结果被强制钳位在 [-0.15, 0.45] 之间，防止恶性通胀或通缩。
/// 
/// # Arguments
/// * `current_heat` - 标准化流速 (单位/秒)
/// * `m1_supply` - 标准化 M1 供应量 (已由 Micros 缩放)
#[inline(always)]
pub fn calculate_inflation_rate(current_heat: f64, m1_supply: f64) -> f64 {
    // 防御性编程: 防止由于极小货币总量导致的数值爆炸
    if m1_supply <= 1.0 { 
        return 0.0; 
    }
    
    let raw_rate = current_heat / m1_supply;
    
    // 硬约束: 通胀率上限 45% (高税收/高价格), 通缩率下限 -15% (补贴/底价保护)
    raw_rate.clamp(-0.15, 0.45)
}

/// 计算市场稳定性因子 (Stability Factor)
/// 
/// 逻辑: 这是一个线性恢复函数。距离上一次大额波动（Volatile Event）越久，
/// 市场信心越足，价格波动弹性越小。
/// 
/// # Arguments
/// * `last_volatile_ts` - 上一次大额波动的时间戳 (ms, i64)
/// * `current_ts` - 当前时间戳 (ms, i64)
/// * `recovery_window_ms` - 恢复 100% 稳定性所需的毫秒数 (默认 900,000ms)
#[inline(always)]
pub fn calculate_stability(
    last_volatile_ts: i64, 
    current_ts: i64, 
    recovery_window_ms: f64
) -> f64 {
    // 如果从未发生过波动 (0), 市场处于完美稳定状态
    if last_volatile_ts <= 0 { 
        return 1.0; 
    }
    
    // 使用 i64 差值转 f64 确保大跨度时间下的计算安全
    let diff = (current_ts - last_volatile_ts) as f64;
    
    if diff < 0.0 {
        return 1.0; // 保护: 处理系统时间回拨
    }
    
    // 归一化结果: [0.0 (恐慌/波动极高) -> 1.0 (平静/完全稳定)]
    (diff / recovery_window_ms).clamp(0.0, 1.0)
}

/// 计算热度自然衰减量 (Decay Amount)
/// 
/// 逻辑: 市场热度（累积交易量）随时间回归。
/// 使用了“归零阈值”逻辑：当热度低于 1.0 (即 1,000,000 Micros) 时，
/// 将触发一次性强制归零，防止内存中残留极微小的浮点碎屑。
/// 
/// # Arguments
/// * `current_heat` - 当前累积热度 (标准化单位)
/// * `daily_decay_rate` - 每日衰减率 (如 0.05 代表 5%)
/// * `cycles_per_day` - 每日任务频率
#[inline(always)]
pub fn calculate_decay(current_heat: f64, daily_decay_rate: f64, cycles_per_day: f64) -> f64 {
    // 归零逻辑：如果热度绝对值小于 1.0 标准单位，则直接返回当前值进行全量扣除
    if current_heat.abs() < 1.0 { 
        return current_heat; 
    }
    
    let per_cycle_rate = daily_decay_rate / cycles_per_day;
    
    // 返回本周期应扣减的标准化金额
    current_heat * per_cycle_rate
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_v1_6_inflation_precision() {
        // 模拟 M1 为 1,000.0 (即 Java 层的 1,000,000,000 Micros)
        assert_eq!(calculate_inflation_rate(100.0, 1000.0), 0.10);
        assert_eq!(calculate_inflation_rate(5000.0, 1000.0), 0.45);
    }

    #[test]
    fn test_stability_monotonicity() {
        let window = 1000.0;
        assert_eq!(calculate_stability(1000, 1500, window), 0.5);
        assert_eq!(calculate_stability(1000, 2500, window), 1.0);
    }

    #[test]
    fn test_decay_zeroing_threshold() {
        // 验证归零逻辑
        let small_heat = 0.5; // 低于 1.0
        assert_eq!(calculate_decay(small_heat, 0.05, 48.0), 0.5);
        
        // 验证正常比例衰减
        let large_heat = 1000.0;
        assert!(calculate_decay(large_heat, 0.48, 48.0) - 10.0 < f64::EPSILON);
    }
}