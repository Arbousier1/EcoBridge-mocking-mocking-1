package top.ellan.ecobridge.infrastructure.ffi.model;

/**
 * NativeTransferResult
 * <p>
 * 映射 Rust 内核 (regulator.rs) 返回的监管计算结果。
 * 对应 Rust 结构体:
 * <pre>
 * #[repr(C)]
 * pub struct TransferResult {
 * pub final_tax: c_double,
 * pub is_blocked: c_int,
 * pub warning_code: c_int,
 * }
 * </pre>
 */
public record NativeTransferResult(
    double finalTax,    // Rust 精确计算的税费 (包含奢侈税、流速惩罚等)
    boolean isBlocked,  // 是否被 Rust 监管拦截 (true = 拦截)
    int warningCode     // 风险等级代码 (对应的 STATUS_CODE)
) {
    // 默认通过状态 (用于降级或初始化)
    public static final NativeTransferResult PASS = new NativeTransferResult(0.0, false, 0);

    // 错误回退状态 (当 FFI 调用失败时使用)
    public static final NativeTransferResult FFI_ERROR = new NativeTransferResult(0.0, true, -1);
}