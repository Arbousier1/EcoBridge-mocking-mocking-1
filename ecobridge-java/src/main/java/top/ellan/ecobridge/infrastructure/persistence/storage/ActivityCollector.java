package top.ellan.ecobridge.infrastructure.persistence.storage;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import top.ellan.ecobridge.EcoBridge;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活跃度采集器 (ActivityCollector v1.2 - Async Safe)
 * <p>
 * 修复日志：
 * 1. [Fix] 增加 PlayerJoinEvent 监听器 (LOWEST 优先级)，确保玩家进服瞬间立即建立缓存。
 * 2. [Fix] 解决异步任务在缓存未命中时获取到错误的“新手状态”问题。
 */
public final class ActivityCollector {

    private static final Map<UUID, ActivitySnapshot> SNAPSHOT_CACHE = new ConcurrentHashMap<>();
    
    private static final long TICKS_PER_SECOND = 20L;
    private static final double SECONDS_PER_HOUR = 3600.0;
    private static final double DEFAULT_NEWBIE_LIMIT = 48.0;

    public record ActivitySnapshot(long seconds, double hours, double activityScore, int isNewbie) {
        // 修复 TransferManager 调用的兼容性方法
        public long playTimeSeconds() { return seconds; }
    }

    private ActivityCollector() {}

    /**
     * 初始化采集器 (必须在插件启动时调用)
     * 注册监听器并启动心跳任务
     */
    public static void init(@NotNull EcoBridge plugin) {
        // 1. 注册进服/退服监听器
        Bukkit.getPluginManager().registerEvents(new Listener() {
            // 使用 LOWEST 优先级，确保我们在其他插件或异步任务查询之前先填充数据
            @EventHandler(priority = EventPriority.LOWEST)
            public void onJoin(PlayerJoinEvent event) {
                updateSnapshot(event.getPlayer());
            }

            @EventHandler
            public void onQuit(PlayerQuitEvent event) {
                removePlayer(event.getPlayer().getUniqueId());
            }
        }, plugin);

        // 2. 针对插件重载的情况，立即更新当前所有在线玩家
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateSnapshot(player);
        }

        // 3. 启动定时同步心跳
        startHeartbeat(plugin);
    }

    public static ActivitySnapshot capture(Player player, double newbieThresholdHours) {
        ActivitySnapshot snapshot = SNAPSHOT_CACHE.get(player.getUniqueId());
        
        // 双重保险：如果缓存依然为空（极低概率），且在主线程，则立即更新
        if (snapshot == null) {
            if (Bukkit.isPrimaryThread()) {
                updateSnapshot(player);
                return SNAPSHOT_CACHE.getOrDefault(player.getUniqueId(), new ActivitySnapshot(0, 0, 0, 1));
            }
            // 如果在异步线程且缓存未命中（通常意味着 init 未正确调用或玩家刚进服毫秒级并发），
            // 只能返回默认值以避免 Bukkit API 报错。
            // 但由于 init() 中有了 JoinListener，这种情况几乎已被根除。
            return new ActivitySnapshot(0, 0, 0, 1);
        }
        return snapshot;
    }

    @NotNull
    public static ActivitySnapshot getSafeSnapshot(@NotNull UUID uuid) {
        return SNAPSHOT_CACHE.getOrDefault(uuid, new ActivitySnapshot(0, 0, 0, 1));
    }

    public static void updateSnapshot(@NotNull Player player) {
        // 必须在主线程访问 getStatistic
        if (!Bukkit.isPrimaryThread()) return;

        try {
            long totalTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
            long totalSeconds = totalTicks / TICKS_PER_SECOND;
            double hours = (double) totalSeconds / SECONDS_PER_HOUR;
            // 简单归一化：20小时达到满活跃分
            double score = Math.min(1.0, (double) totalSeconds / 72000.0);
            int newbieBit = (hours < DEFAULT_NEWBIE_LIMIT) ? 1 : 0;

            SNAPSHOT_CACHE.put(player.getUniqueId(), new ActivitySnapshot(totalSeconds, hours, score, newbieBit));
        } catch (Exception e) {
            // 防止获取统计数据异常导致流程中断
            e.printStackTrace();
        }
    }

    public static void removePlayer(@NotNull UUID uuid) {
        SNAPSHOT_CACHE.remove(uuid);
    }

    private static void startHeartbeat(@NotNull EcoBridge plugin) {
        // 每 2 分钟 (2400 ticks) 同步一次所有在线玩家数据
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                updateSnapshot(player);
            }
        }, 100L, 2400L);
    }

    public static double getScore(@NotNull UUID uuid) {
        return getSafeSnapshot(uuid).activityScore();
    }

    public static Component toComponent(Player player) {
        var snapshot = getSafeSnapshot(player.getUniqueId());
        String color = snapshot.hours() < 10 ? "<red>" : (snapshot.hours() < 50 ? "<yellow>" : "<green>");
        double displayHours = Math.floor(snapshot.hours() * 10) / 10.0;

        return MiniMessage.miniMessage().deserialize(
            "<gray>活跃等级: " + color + "<hours>h <dark_gray>(<sec>s) <gray>新手状态: <newbie>",
            Placeholder.unparsed("hours", String.valueOf(displayHours)),
            Placeholder.unparsed("sec", String.valueOf(snapshot.seconds())),
            Placeholder.unparsed("newbie", snapshot.isNewbie() == 1 ? "<yellow>是" : "<green>否")
        );
    }
}