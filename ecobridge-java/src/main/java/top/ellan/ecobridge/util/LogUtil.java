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
 * Logging utility with shutdown-safe async behavior.
 */
public final class LogUtil {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final AtomicLong TRANSACTION_COUNTER = new AtomicLong(0);

    private static final Map<String, Long> RATE_LIMIT_CACHE = new ConcurrentHashMap<>();
    private static final long LOG_COOLDOWN_MS = 5 * 60 * 1000;

    private static volatile boolean debugEnabled = false;
    private static volatile int sampleRate = 100;

    private LogUtil() {
    }

    public static void init() {
        var config = EcoBridge.getInstance().getConfig();
        debugEnabled = config.getBoolean("system.debug", false);
        sampleRate = Math.max(1, config.getInt("system.log-sample-rate", 100));
        RATE_LIMIT_CACHE.clear();

        if (debugEnabled) {
            info("<gradient:aqua:blue>Debug mode enabled</gradient> <dark_gray>| <gray>sample rate <white>1/<rate>",
                Placeholder.unparsed("rate", String.valueOf(sampleRate)));
        }
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static void info(String message, TagResolver... resolvers) {
        sendConsole("<blue>[INFO]</blue> <gray>" + message, resolvers);
    }

    public static void debug(String message) {
        if (debugEnabled) {
            sendConsole("<dark_gray>[DEBUG]</dark_gray> <gray>" + message);
        }
    }

    public static void warn(String message) {
        sendConsole("<yellow>[WARN]</yellow> <white>" + message);
    }

    public static void warnOnce(String key, String message) {
        if (shouldLog(key)) {
            sendConsole("<yellow>[WARN]</yellow> <white>" + message + " <dark_gray>(rate-limited)</dark_gray>");
        }
    }

    public static void errorOnce(String key, String message) {
        if (shouldLog(key)) {
            sendConsole("<red>[ERROR]</red> <white>" + message + " <dark_gray>(rate-limited)</dark_gray>");
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

    public static void severe(String message) {
        sendConsole("<red>[SEVERE]</red> <bold><red>Fatal: </red></bold><white>" + message);
    }

    public static void severe(String message, Throwable e) {
        EcoBridge plugin = EcoBridge.getInstanceOrNull();
        if (plugin != null && plugin.isEnabled()) {
            plugin.getVirtualExecutor().execute(() -> renderSevereBox(message, e));
        } else {
            renderSevereBox(message, e);
        }
    }

    private static void renderSevereBox(String message, Throwable e) {
        sendConsole("<dark_red>============================================================</dark_red>");
        sendConsole("<dark_red>[SEVERE]</dark_red> <white><msg>", Placeholder.unparsed("msg", message));

        if (e != null) {
            sendConsole("<dark_red>Type:</dark_red> <yellow><type>", Placeholder.unparsed("type", e.getClass().getSimpleName()));
            sendConsole("<dark_red>Reason:</dark_red> <gray><reason>", Placeholder.unparsed("reason", String.valueOf(e.getMessage())));
            sendConsole("<dark_red>------------------------------------------------------------</dark_red>");

            EcoBridge plugin = EcoBridge.getInstanceOrNull();
            if (plugin != null) {
                plugin.getLogger().severe("--- Detailed stacktrace (Critical Failure) ---");
            } else {
                Bukkit.getLogger().severe("[EcoBridge] --- Detailed stacktrace (Critical Failure/Shutdown) ---");
            }
            e.printStackTrace();
        } else {
            sendConsole("<dark_red>============================================================</dark_red>");
        }
    }

    public static void logTransactionSampled(String message, TagResolver... resolvers) {
        if (!debugEnabled) {
            return;
        }

        EcoBridge plugin = EcoBridge.getInstanceOrNull();
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }

        long count = TRANSACTION_COUNTER.incrementAndGet();
        if (count % sampleRate == 0) {
            plugin.getVirtualExecutor().execute(() -> {
                TagResolver combined = TagResolver.resolver(
                    TagResolver.resolver(resolvers),
                    Placeholder.unparsed("count", String.valueOf(count))
                );
                sendConsole("<blue>[TX]</blue> <gray>" + message + " <dark_gray>(#<count>)</dark_gray>", combined);
            });
        }
    }

    public static void error(String message) {
        error(message, null);
    }

    public static void error(String message, Throwable e) {
        EcoBridge plugin = EcoBridge.getInstanceOrNull();
        if (plugin != null && plugin.isEnabled()) {
            plugin.getVirtualExecutor().execute(() -> renderErrorBox(message, e));
        } else {
            renderErrorBox(message, e);
        }
    }

    private static void renderErrorBox(String message, Throwable e) {
        sendConsole("<red>============================================================</red>");
        sendConsole("<red>[ERROR]</red> <white><msg>", Placeholder.unparsed("msg", message));

        if (e != null) {
            sendConsole("<red>Type:</red> <yellow><type>", Placeholder.unparsed("type", e.getClass().getSimpleName()));
            sendConsole("<red>Reason:</red> <gray><reason>", Placeholder.unparsed("reason", String.valueOf(e.getMessage())));
            sendConsole("<red>------------------------------------------------------------</red>");

            EcoBridge plugin = EcoBridge.getInstanceOrNull();
            if (plugin != null) {
                plugin.getLogger().severe("--- Detailed stacktrace ---");
            } else {
                Bukkit.getLogger().severe("[EcoBridge] --- Detailed stacktrace (Shutdown) ---");
            }
            e.printStackTrace();
        } else {
            sendConsole("<red>============================================================</red>");
        }
    }

    private static void sendConsole(String msg, TagResolver... resolvers) {
        Bukkit.getConsoleSender().sendMessage(MM.deserialize(msg, resolvers));
    }
}