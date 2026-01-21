package top.ellan.ecobridge.application.context;

/**
 * 交易上下文 (TransactionContext)
 * 职责：利用 ThreadLocal 在当前线程中标记交易属性。
 * 作用：帮助 CoinsEngineListener 区分由 EcoBridge 插件发起的“内部市场交易”与由控制台/其他插件触发的“外部余额变动”。
 */
public class TransactionContext {

    // 默认值为 false，表示如果不显式标记，所有变动都视为非市场行为（外部变动）
    private static final ThreadLocal<Boolean> IS_MARKET_TRADE = ThreadLocal.withInitial(() -> false);

    /**
     * 设置当前线程是否正在处理市场交易
     * @param value true=市场交易(计入热度), false=非市场交易
     */
    public static void setMarketTrade(boolean value) {
        IS_MARKET_TRADE.set(value);
    }

    /**
     * 检查当前线程是否标记为市场交易
     * @return true if market trade
     */
    public static boolean isMarketTrade() {
        return IS_MARKET_TRADE.get();
    }

    /**
     * 清除标记，防止内存泄漏或线程复用导致的状态污染
     * 必须在 finally 块中调用
     */
    public static void clear() {
        IS_MARKET_TRADE.remove();
    }
}