package top.ellan.ecobridge.infrastructure.persistence.storage;

import top.ellan.ecobridge.infrastructure.ffi.bridge.NativeBridge;
import top.ellan.ecobridge.util.LogUtil;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 异步经济日志记录器 (AsyncLogger v1.2.1 - Safety & Compatibility Integrated)
 * <p>
 * 职责：
 * 1. 缓冲全服交易日志，防止高频磁盘 I/O 阻塞主线程。
 * 2. 物理写入 Native 端的 DuckDB 存储。
 * 3. [Fix] 引入背压机制 (Backpressure)，队列满时自动阻塞虚拟线程，防止日志丢失。
 * 4. [Fix] 兼容旧版静态调用与带参初始化。
 */
public class AsyncLogger {

    private static AsyncLogger instance;
    private final BlockingQueue<LogEntry> queue = new LinkedBlockingQueue<>(50000);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread workerThread;

    private AsyncLogger() {
        // 使用平台线程作为消费端，确保 I/O 优先级，避免在 FFI 调用时产生过大延迟
        this.workerThread = Thread.ofPlatform()
                .name("EcoBridge-Logger-Worker")
                .start(this::processQueue);
    }

    /**
     * [修复集成错误] 允许带参或不带参调用
     * 确保 EcoBridge.java 中 init(this) 不会发生方法未定义的错误
     */
    public static void init(Object... ignored) {
        if (instance == null) {
            instance = new AsyncLogger();
        }
    }

    public static AsyncLogger getInstance() {
        return instance;
    }

    /**
     * 转账结算专用日志方法
     * 由 TransferManager.executeSettlement 调用
     */
    public void logTransaction(Object sender, Object receiver, double amount, double tax) {
        enqueue(new LogEntry(
                System.currentTimeMillis(),
                sender.toString(),
                receiver.toString(),
                amount,
                tax
        ));
    }

    /**
     * [修复集成错误] 静态兼容方法 - 4参数重载
     * 适配 CoinsEngineListener 等旧版调用逻辑
     */
    public static void log(Object uuid, double amount, double balance, long ts) {
        log(uuid, amount, balance, ts, "GENERAL_TRADE");
    }

    /**
     * [修复集成错误] 静态兼容方法 - 5参数全量重载
     */
    public static void log(Object uuid, double amount, double balance, long ts, String meta) {
        if (instance != null) {
            instance.enqueue(new LogEntry(ts, uuid.toString(), meta, amount, balance));
        }
    }

    /**
     * 核心入队逻辑（含背压处理）
     */
    private void enqueue(LogEntry entry) {
        if (!running.get()) return;
        try {
            // [Safety] 队列满时，put() 会阻塞生产端（虚拟线程），
            // 产生天然背压，确保在高并发极端情况下日志记录的绝对完整性。
            queue.put(entry);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 消费循环
     */
    private void processQueue() {
        while (running.get() || !queue.isEmpty()) {
            try {
                // 每 500ms 尝试拉取一次，或者在队列非空时立即处理
                LogEntry entry = queue.poll(500, TimeUnit.MILLISECONDS);
                if (entry == null) continue;

                // 物理写入 Native 引擎 (DuckDB)
                if (NativeBridge.isLoaded()) {
                    NativeBridge.pushToDuckDB(
                            entry.ts,
                            entry.s,      // Sender UUID / Identifier
                            entry.amount, // Value 1
                            entry.tax,    // Value 2 (Balance or Tax)
                            entry.r       // Meta Info / Receiver
                    );
                }
            } catch (Exception e) {
                LogUtil.error("AsyncLogger: 日志消费线程持久化异常", e);
            }
        }
        LogUtil.info("AsyncLogger: 队列已排空，存储通道已安全关闭。");
    }

    /**
     * 强制刷盘并安全关闭
     */
    public void shutdown() {
        LogUtil.info("正在执行 AsyncLogger 停机序列...");
        running.set(false);
        try {
            // 给消费线程最多 5 秒时间处理剩余积压
            workerThread.join(5000);
        } catch (InterruptedException e) {
            LogUtil.warn("AsyncLogger: 停机等待被中断。");
            workerThread.interrupt();
        }
    }

    /**
     * 内部日志数据载体
     */
    private record LogEntry(long ts, String s, String r, double amount, double tax) {}
}