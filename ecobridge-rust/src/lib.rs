// ==================================================
// FILE: ecobridge-rust/src/lib.rs
// ==================================================

use libc::{c_char, c_double, c_int, c_longlong}; 
use std::ffi::CStr;
use std::panic::{self, AssertUnwindSafe};
use std::sync::atomic::{AtomicI64, Ordering};
use std::ptr;

// -----------------------------------------------------------------------------
// 模块声明
// -----------------------------------------------------------------------------
pub mod models;
pub mod economy {
    pub mod pricing;
    pub mod summation;
    pub mod environment;
    pub mod control;
    pub mod macro_eco;
}
pub mod security;
pub mod storage;

use crate::models::*;

// -----------------------------------------------------------------------------
// 0. 错误通讯协议 (The Protocol)
// -----------------------------------------------------------------------------

#[repr(i32)]
#[derive(Copy, Clone, Debug)]
pub enum EconStatus {
    Ok = 0,
    NullPointer = 1,
    InvalidLength = 2,
    InvalidValue = 3,
    NumericOverflow = 10,
    InternalError = 100,
    Panic = 101,
    Fatal = 255,
}

// -----------------------------------------------------------------------------
// 全局状态
// -----------------------------------------------------------------------------
static REMOTE_FLOW_ACCUMULATOR_MICROS: AtomicI64 = AtomicI64::new(0);
const MICROS_SCALE: f64 = 1_000_000.0;

// -----------------------------------------------------------------------------
// FFI 安全屏障 (The Firewall)
// -----------------------------------------------------------------------------

macro_rules! ffi_guard {
    ($body:expr) => {{
        let result = panic::catch_unwind(AssertUnwindSafe($body));
        match result {
            Ok(status) => status as c_int,
            Err(e) => {
                let msg = if let Some(s) = e.downcast_ref::<&str>() {
                    *s
                } else if let Some(s) = e.downcast_ref::<String>() {
                    s.as_str()
                } else {
                    "Unknown panic"
                };
                eprintln!("[EcoBridge-Native] PANIC INTERCEPTED: {}", msg);
                EconStatus::Panic as c_int
            }
        }
    }};
}

// -----------------------------------------------------------------------------
// 1. 系统基础与并发控制
// -----------------------------------------------------------------------------

#[no_mangle]
pub extern "C" fn ecobridge_abi_version() -> c_int {
    0x0009_0000 
}

#[no_mangle]
pub extern "C" fn ecobridge_version() -> *const c_char {
    static VERSION: &[u8] = b"EcoBridge Native Core v1.6.0-PrecisionIntegrated\0";
    VERSION.as_ptr() as *const c_char
}

#[no_mangle]
pub extern "C" fn ecobridge_init_threading(num_threads: c_int) -> c_int {
    let config = rayon::ThreadPoolBuilder::new().num_threads(num_threads as usize);
    match config.build_global() {
        Ok(_) => EconStatus::Ok as c_int,
        Err(_) => EconStatus::InternalError as c_int
    }
}

// -----------------------------------------------------------------------------
// 2. 存储与监控
// -----------------------------------------------------------------------------

#[no_mangle]
pub extern "C" fn ecobridge_init_db(path_ptr: *const c_char) -> c_int {
    ffi_guard!(|| {
        if path_ptr.is_null() {
            return EconStatus::NullPointer;
        }
        let path_result = unsafe { CStr::from_ptr(path_ptr).to_str() };
        
        match path_result {
            Ok(path_str) => {
                match storage::init_economy_db(path_str) {
                    0 => {
                        economy::summation::hydrate_hot_store();
                        EconStatus::Ok
                    },
                    _ => EconStatus::Fatal
                }
            },
            Err(_) => EconStatus::InvalidValue,
        }
    })
}

#[no_mangle]
pub extern "C" fn ecobridge_shutdown_db() -> c_int {
    ffi_guard!(|| {
        storage::shutdown_db_internal();
        EconStatus::Ok
    })
}

#[no_mangle]
pub unsafe extern "C" fn ecobridge_log_to_duckdb(
    ts: c_longlong,
    uuid_ptr: *const c_char,
    trade_amount_micros: c_longlong, 
    balance_micros: c_longlong,      
    meta_ptr: *const c_char,
) -> c_int {
    ffi_guard!(|| {
        if uuid_ptr.is_null() || meta_ptr.is_null() {
            return EconStatus::NullPointer;
        }
        let uuid = CStr::from_ptr(uuid_ptr).to_string_lossy().into_owned();
        let meta = CStr::from_ptr(meta_ptr).to_string_lossy().into_owned();
        
        let amount_f64 = (trade_amount_micros as f64) / MICROS_SCALE;
        let balance_f64 = (balance_micros as f64) / MICROS_SCALE;
        
        economy::summation::append_trade_to_memory(ts, amount_f64.abs());
        storage::log_economy_event(ts, uuid, amount_f64, balance_f64, meta);
        
        EconStatus::Ok
    })
}

// -----------------------------------------------------------------------------
// 3. 核心计算
// -----------------------------------------------------------------------------

#[no_mangle]
pub extern "C" fn inject_remote_trade(amount_micros: c_longlong) -> c_int {
    ffi_guard!(|| {
        REMOTE_FLOW_ACCUMULATOR_MICROS.fetch_add(amount_micros.abs(), Ordering::SeqCst);
        EconStatus::Ok
    })
}

#[no_mangle]
pub unsafe extern "C" fn ecobridge_query_neff_vectorized(
    current_ts: c_longlong,
    tau: c_double,
    out_result: *mut c_double,
) -> c_int {
    ffi_guard!(|| {
        if out_result.is_null() { return EconStatus::NullPointer; }
        if tau <= 0.0 { return EconStatus::InvalidValue; }

        let local_neff = economy::summation::query_neff_internal(current_ts, tau);
        let remote_micros = REMOTE_FLOW_ACCUMULATOR_MICROS.swap(0, Ordering::SeqCst);
        let remote_neff = (remote_micros as f64) / MICROS_SCALE;
        
        *out_result = local_neff + remote_neff;
        EconStatus::Ok
    })
}

#[no_mangle]
pub unsafe extern "C" fn ecobridge_compute_batch_prices(
    count: u64,
    neff: f64,
    ctx_ptr: *const TradeContext,
    cfg_ptr: *const MarketConfig,
    hist_avgs_ptr: *const f64,
    lambdas_ptr: *const f64,
    results_ptr: *mut f64,
) -> c_int {
    ffi_guard!(|| {
        if ctx_ptr.is_null() || cfg_ptr.is_null() || hist_avgs_ptr.is_null() || 
           lambdas_ptr.is_null() || results_ptr.is_null() {
            return EconStatus::NullPointer;
        }
        
        if count == 0 { return EconStatus::Ok; }
        if count > 1_000_000 { return EconStatus::InvalidLength; }

        economy::pricing::compute_batch_prices_internal(
            count as usize,
            neff,
            ctx_ptr,
            cfg_ptr,
            hist_avgs_ptr,
            lambdas_ptr,
            results_ptr
        );
        
        EconStatus::Ok
    })
}

// --- 单体价格计算函数 (Fix: 适配 i64 Micros 参数) ---

#[no_mangle]
pub unsafe extern "C" fn ecobridge_compute_price_final(
    base: c_double,
    n_eff: c_double,
    lambda: c_double,
    epsilon: c_double,
    out_result: *mut c_double,
) -> c_int {
    ffi_guard!(|| {
        if out_result.is_null() { return EconStatus::NullPointer; }
        // [Precision Fix]: 将 c_double base 转换为 i64 Micros
        let base_micros = (base * MICROS_SCALE) as i64;
        *out_result = economy::pricing::compute_price_final_internal(base_micros, n_eff, lambda, epsilon);
        EconStatus::Ok
    })
}

#[no_mangle]
pub unsafe extern "C" fn ecobridge_compute_tier_price(
    base: c_double,
    qty: c_double,
    is_sell: c_int,
    out_result: *mut c_double,
) -> c_int {
    ffi_guard!(|| {
        if out_result.is_null() { return EconStatus::NullPointer; }
        *out_result = economy::pricing::compute_tier_price_internal(base, qty, is_sell != 0);
        EconStatus::Ok
    })
}

#[no_mangle]
pub unsafe extern "C" fn ecobridge_compute_price_humane(
    base: c_double,
    n_eff: c_double,
    trade_amount: c_double,
    lambda: c_double,
    epsilon: c_double,
    out_result: *mut c_double,
) -> c_int {
    ffi_guard!(|| {
        if out_result.is_null() { return EconStatus::NullPointer; }
        // [Precision Fix]: 将 base 和 trade_amount 转换为 i64 Micros
        let base_micros = (base * MICROS_SCALE) as i64;
        let amount_micros = (trade_amount * MICROS_SCALE) as i64;
        *out_result = economy::pricing::compute_price_humane_internal(base_micros, n_eff, amount_micros, lambda, epsilon);
        EconStatus::Ok
    })
}

#[no_mangle]
pub unsafe extern "C" fn ecobridge_compute_price_bounded(
    base: c_double,
    n_eff: c_double,
    amt: c_double,
    lambda: c_double,
    eps: c_double,
    hist_avg: c_double,
    out_result: *mut c_double,
) -> c_int {
    ffi_guard!(|| {
        if out_result.is_null() { return EconStatus::NullPointer; }
        // [Precision Fix]: 将 base 和 amt 转换为 i64 Micros
        let base_micros = (base * MICROS_SCALE) as i64;
        let amt_micros = (amt * MICROS_SCALE) as i64;
        *out_result = economy::pricing::compute_price_bounded_internal(base_micros, n_eff, amt_micros, lambda, eps, hist_avg);
        EconStatus::Ok
    })
}

// -----------------------------------------------------------------------------
// 4. 宏观经济指标
// -----------------------------------------------------------------------------

#[no_mangle]
pub unsafe extern "C" fn ecobridge_calc_inflation(
    current_heat: c_double,
    m1: c_double,
    out_result: *mut c_double,
) -> c_int {
    ffi_guard!(|| {
        if out_result.is_null() { return EconStatus::NullPointer; }
        if m1 <= 0.0 { return EconStatus::InvalidValue; }
        *out_result = economy::macro_eco::calculate_inflation_rate(current_heat, m1);
        EconStatus::Ok
    })
}

#[no_mangle]
pub unsafe extern "C" fn ecobridge_calc_stability(
    last_ts: c_longlong,
    curr_ts: c_longlong,
    out_result: *mut c_double,
) -> c_int {
    ffi_guard!(|| {
        if out_result.is_null() { return EconStatus::NullPointer; }
        *out_result = economy::macro_eco::calculate_stability(last_ts, curr_ts, 900000.0);
        EconStatus::Ok
    })
}

#[no_mangle]
pub unsafe extern "C" fn ecobridge_calc_decay(
    heat: c_double,
    rate: c_double,
    out_result: *mut c_double,
) -> c_int {
    ffi_guard!(|| {
        if out_result.is_null() { return EconStatus::NullPointer; }
        *out_result = economy::macro_eco::calculate_decay(heat, rate, 48.0);
        EconStatus::Ok
    })
}

#[no_mangle]
pub unsafe extern "C" fn ecobridge_get_health_stats(
    out_total: *mut u64, 
    out_dropped: *mut u64,
) -> c_int {
    ffi_guard!(|| {
        if out_total.is_null() || out_dropped.is_null() {
            return EconStatus::NullPointer;
        }
        *out_total = storage::get_total_logs() as u64; 
        *out_dropped = storage::get_dropped_logs() as u64;
        EconStatus::Ok
    })
}

#[no_mangle]
pub unsafe extern "C" fn ecobridge_calculate_epsilon(
    ctx_ptr: *const TradeContext,
    cfg_ptr: *const MarketConfig,
    out_result: *mut c_double,
) -> c_int {
    ffi_guard!(|| {
        if ctx_ptr.is_null() || cfg_ptr.is_null() || out_result.is_null() {
            return EconStatus::NullPointer;
        }
        *out_result = economy::environment::calculate_epsilon_internal(&*ctx_ptr, &*cfg_ptr);
        EconStatus::Ok
    })
}

// -----------------------------------------------------------------------------
// 5. 安全审计与动态限额
// -----------------------------------------------------------------------------

#[no_mangle]
pub unsafe extern "C" fn ecobridge_compute_transfer_check(
    out_result: *mut TransferResult,
    ctx_ptr: *const TransferContext,
    cfg_ptr: *const RegulatorConfig,
) -> c_int {
    ffi_guard!(|| {
        if out_result.is_null() || ctx_ptr.is_null() || cfg_ptr.is_null() {
            return EconStatus::NullPointer;
        }

        let res = security::regulator::compute_transfer_check_internal(&*ctx_ptr, &*cfg_ptr);
        ptr::write(out_result, res);
        EconStatus::Ok
    })
}

#[no_mangle]
pub unsafe extern "C" fn ecobridge_get_dynamic_limit(
    play_time_secs: c_longlong,
    base: c_double,
    rate: c_double,
    max: c_double,
    out_result: *mut c_double,
) -> c_int {
    ffi_guard!(|| {
        if out_result.is_null() { return EconStatus::NullPointer; }
        
        let hours = (play_time_secs as f64) / 3600.0;
        let calculated = base + (rate * hours.sqrt());
        *out_result = calculated.min(max);
        
        EconStatus::Ok
    })
}

// -----------------------------------------------------------------------------
// 6. PID 控制
// -----------------------------------------------------------------------------

#[no_mangle]
pub unsafe extern "C" fn ecobridge_compute_pid_adjustment(
    pid_ptr: *mut PidState,
    target: c_double,
    current: c_double,
    dt: c_double,
    inflation: c_double,
    market_heat: c_double,
    out_result: *mut c_double,
) -> c_int {
    ffi_guard!(|| {
        if pid_ptr.is_null() || out_result.is_null() { 
            return EconStatus::NullPointer; 
        }
        if let Some(pid) = pid_ptr.as_mut() {
            *out_result = economy::control::compute_pid_adjustment_internal(
                pid, target, current, dt, inflation, market_heat
            );
            EconStatus::Ok
        } else {
            return EconStatus::NullPointer;
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn ecobridge_reset_pid_state(pid_ptr: *mut PidState) -> c_int {
    ffi_guard!(|| {
        if let Some(pid) = pid_ptr.as_mut() {
            *pid = PidState::default();
            EconStatus::Ok
        } else {
            return EconStatus::NullPointer
        }
    })
}