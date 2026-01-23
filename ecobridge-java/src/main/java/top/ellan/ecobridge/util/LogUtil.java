package top.ellan.ecobridge.util;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import top.ellan.ecobridge.EcoBridge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工业级日志工具类 (LogUtil v0.9.2 - Shutdown Safe)
 * <p>
 * 更新日志:
 * 1. [Fix] 修复在插件卸载/异步回调时调用 getInstance() 导致的 IllegalStateException。
 * 2. [Safety] 引入防御性编程，当插件实例不可用时自动降级为同步日志，防止吞掉报错。
 */
public final class LogUtil {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final AtomicLong TRANSACTION_COUNTER = new AtomicLong(0);
    
    private static final Map<String, Long> RATE_LIMIT_CACHE = new ConcurrentHashMap<>();
    private static final long LOG_COOLDOWN_MS = 5 * 60 * 1000; // 5分钟冷却

    private static volatile boolean debugEnabled = false;
    private static volatile int sampleRate = 100;

    private LogUtil() {}

    public static void init() {
        // 这里必须使用 getInstance，因为 init 肯定是在插件启动时调用的
        var config = EcoBridge.getInstance().getConfig();
        debugEnabled = config.getBoolean("system.debug", false);
        sampleRate = Math.max(1, config.getInt("system.log-sample-rate", 100));
        RATE_LIMIT_CACHE.clear(); 

        if (debugEnabled) {
            info("<gradient:aqua:blue>系统调试模式已激活</gradient> <dark_gray>| <gray>采样率: <white>1/<rate>",
            Placeholder.unparsed("rate", String.valueOf(sampleRate)));
        }
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static void info(String message, TagResolver... resolvers) {
        sendConsole("<blue>ℹ</blue> <gray>" + message, resolvers);
    }

    public static void debug(String message) {
        if (debugEnabled) {
            sendConsole("<dark_gray>[DEBUG]</dark_gray> <gray>" + message);
        }
    }

    public static void warn(String message) {
        sendConsole("<yellow>⚠</yellow> <white>" + message);
    }

    public static void warnOnce(String key, String message) {
        if (shouldLog(key)) {
            sendConsole("<yellow>⚠</yellow> <white>" + message + " <dark_gray>(已折叠同类警告)</dark_gray>");
        }
    }

    public static void errorOnce(String key, String message) {
        if (shouldLog(key)) {
            sendConsole("<red>✘</red> <white>" + message + " <dark_gray>(已折叠同类错误)</dark_gray>");
        }
    }

    private static boolean shouldLog(String key) {
        long now = System.currentTimeMillis();
        Long last = RATE_LIMIT_CACHE.get(key);
        if (last == null || (now - last) > LOG_COOLDOWN_MS) {
            RATE_LIMIT_CACHE.put(key, now);
            return true;
        }
        return false;
    }

    // --- Severe (致命故障) 系列方法 ---

    public static void severe(String message) {
        sendConsole("<red>✘</red> <bold><red>致命故障: </red></bold><white>" + message);
    }

    /**
     * 带有异常堆栈的致命错误日志
     * [Fix] 增加生命周期检查，防止关机崩溃
     */
    public static void severe(String message, Throwable e) {
        // 如果插件还活著，扔进虚拟线程池异步处理
        if (EcoBridge.isInitialized() && EcoBridge.getInstance().isEnabled()) {
            EcoBridge.getInstance().getVirtualExecutor().execute(() -> renderSevereBox(message, e));
        } else {
            // 如果插件已死 (关机中)，直接在当前线程同步打印，确保日志不丢失且不报错
            renderSevereBox(message, e);
        }
    }

    // 抽离渲染逻辑，复用于异步和同步场景
    private static void renderSevereBox(String message, Throwable e) {
        sendConsole("<dark_red>╔══════════════ ⛔ 致命故障 (SEVERE) ══════════════");
        sendConsole("<dark_red>║ <white>消息: <msg>", Placeholder.unparsed("msg", message));
        
        if (e != null) {
            sendConsole("<dark_red>║ <white>类型: <yellow><type>", Placeholder.unparsed("type", e.getClass().getSimpleName()));
            sendConsole("<dark_red>║ <white>原因: <gray><reason>", Placeholder.unparsed("reason", String.valueOf(e.getMessage())));
            sendConsole("<dark_red>╚════════════════════════════════════════════════");
            
            // 安全地获取 Logger
            if (EcoBridge.isInitialized()) {
                EcoBridge.getInstance().getLogger().severe("--- 详细堆栈追踪 (Critical Failure) ---");
            } else {
                Bukkit.getLogger().severe("[EcoBridge] --- 详细堆栈追踪 (Critical Failure/Shutdown) ---");
            }
            e.printStackTrace();
        } else {
            sendConsole("<dark_red>╚════════════════════════════════════════════════");
        }
    }

    // --- Transaction (交易采样) ---

    public static void logTransactionSampled(String message, TagResolver... resolvers) {
        if (!debugEnabled) return;

        // 如果插件关闭，停止采样日志，防止提交任务到已关闭的线程池
        if (!EcoBridge.isInitialized() || !EcoBridge.getInstance().isEnabled()) return;

        long count = TRANSACTION_COUNTER.incrementAndGet();
        if (count % sampleRate == 0) {
            EcoBridge.getInstance().getVirtualExecutor().execute(() -> {
                TagResolver combined = TagResolver.resolver(
                    TagResolver.resolver(resolvers),
                    Placeholder.unparsed("count", String.valueOf(count))
                );
                sendConsole("<blue>⚖</blue> <gray>" + message + " <dark_gray>(#<count>)", combined);
            });
        }
    }

    // --- Error (普通错误) 系列方法 ---

    public static void error(String message) {
        error(message, null);
    }

    public static void error(String message, Throwable e) {
        // 防御性检查
        if (EcoBridge.isInitialized() && EcoBridge.getInstance().isEnabled()) {
            EcoBridge.getInstance().getVirtualExecutor().execute(() -> renderErrorBox(message, e));
        } else {
            // 降级为同步执行
            renderErrorBox(message, e);
        }
    }

    private static void renderErrorBox(String message, Throwable e) {
        sendConsole("<red>╔══════════════ EcoBridge 异常报告 ══════════════");
        sendConsole("<red>║ <white>描述: <msg>", Placeholder.unparsed("msg", message));

        if (e != null) {
            sendConsole("<red>║ <white>类型: <yellow><type>", Placeholder.unparsed("type", e.getClass().getSimpleName()));
            sendConsole("<red>║ <white>原因: <gray><reason>", Placeholder.unparsed("reason", String.valueOf(e.getMessage())));
            sendConsole("<red>╚════════════════════════════════════════════════");
            
            if (EcoBridge.isInitialized()) {
                EcoBridge.getInstance().getLogger().severe("--- 详细堆栈追踪 ---");
            } else {
                Bukkit.getLogger().severe("[EcoBridge] --- 详细堆栈追踪 (Shutdown) ---");
            }
            e.printStackTrace();
        } else {
            sendConsole("<red>╚════════════════════════════════════════════════");
        }
    }

    private static void sendConsole(String msg, TagResolver... resolvers) {
        // Bukkit.getConsoleSender() 在关机期间通常仍然可用
        Bukkit.getConsoleSender().sendMessage(MM.deserialize(msg, resolvers));
    }
}