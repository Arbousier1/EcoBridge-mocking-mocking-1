// ==================================================
// FILE: ecobridge-rust/src/security/mod.rs
// ==================================================

//! Security and Compliance Module (v1.6.0 - Precision & Behavioral Audit)
//! 
//! # 职责描述
//! 1. 负责全服交易的高级风险审计。
//! 2. 物理隔绝非法资金流动（基于 i64 Micros 精度协议）。
//! 3. 实现基于平方根模型的物品动态数量限额管理。

// ==================== 1. 子模块声明 ====================

/// 风控核心逻辑实现 (包含动态数量限额演算、账户拆分防御与傀儡账户识别)
pub mod regulator;

// ==================== 2. 跨模块重导出 ====================

/// 重新导出配置结构体 (SSoT)
pub use crate::models::RegulatorConfig;

/// 重新导出核心逻辑函数与状态码
/// 确保所有符号在 security 命名空间下可用
pub use regulator::{
    // 核心审计函数 (已适配 v1.6.0 i64 定点数)
    compute_transfer_check_internal,
    
    // 辅助判断函数
    is_high_risk_transfer,

    // 审计状态码常量
    CODE_NORMAL,                   // 0: 正常交易
    CODE_WARNING_HIGH_RISK,        // 1: 高风险预警
    CODE_BLOCK_REVERSE_FLOW,       // 2: 拦截逆向流转 (玩家间转账逻辑)
    CODE_BLOCK_INJECTION,          // 3: 拦截非正常注资 (管理员/老手非正常转账)
    CODE_BLOCK_INSUFFICIENT_FUNDS, // 4: 拦截余额不足
    CODE_BLOCK_VELOCITY_LIMIT,     // 5: 拦截异常交易频率 (洗钱/刷钱行为)
    
    // 拦截动态数量限额 (物品售出数量超过基于时长的演算上限)
    CODE_BLOCK_QUANTITY_LIMIT,     // 6: 触发平方根模型数量拦截
};