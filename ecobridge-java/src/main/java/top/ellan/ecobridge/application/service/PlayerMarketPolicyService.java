package top.ellan.ecobridge.application.service;

import org.bukkit.configuration.file.FileConfiguration;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.infrastructure.persistence.storage.ActivityCollector;
import top.ellan.ecobridge.util.HolidayManager;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.LongAdder;

/**
 * Personal sell-price policy from the article model:
 * 1) player quota pool
 * 2) player-item decay pricing with time recovery
 * 3) environment/special multipliers
 */
public final class PlayerMarketPolicyService {

    private static volatile PlayerMarketPolicyService instance;

    private final EcoBridge plugin;

    private volatile boolean enabled;
    private volatile boolean quotaEnabled;
    private volatile boolean decayEnabled;
    private volatile boolean epsilonEnabled;
    private volatile boolean specialEnabled;

    private volatile double quotaBase;
    private volatile double quotaGammaPerHour;
    private volatile double quotaGlobalCap;
    private volatile long quotaPeriodHours;

    private volatile double epsilonWeekendFactor;
    private volatile double epsilonHolidayFactor;
    private volatile double epsilonNoiseStdDev;
    private volatile double epsilonMin;
    private volatile double epsilonMax;

    private volatile double decayDelta;
    private volatile double decayTauDays;
    private volatile long decayWindowDays;
    private volatile double minSellMultiplier;

    private final Map<PlayerItemKey, ConcurrentHashMap<Long, LongAdder>> soldBuckets = new ConcurrentHashMap<>();
    private final Map<UUID, ConcurrentLinkedDeque<QuotaUsage>> quotaUsages = new ConcurrentHashMap<>();

    private PlayerMarketPolicyService(EcoBridge plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public static void init(EcoBridge plugin) {
        instance = new PlayerMarketPolicyService(plugin);
    }

    public static PlayerMarketPolicyService getInstance() {
        return instance;
    }

    public void loadConfig() {
        FileConfiguration c = plugin.getConfig();

        enabled = c.getBoolean("economy.player-market.enabled", true);
        quotaEnabled = c.getBoolean("economy.player-market.quota.enabled", true);
        decayEnabled = c.getBoolean("economy.player-market.decay.enabled", true);
        epsilonEnabled = c.getBoolean("economy.player-market.indices.epsilon-enabled", true);
        specialEnabled = c.getBoolean("economy.player-market.indices.special-enabled", true);

        quotaBase = c.getDouble("economy.player-market.quota.base", 64.0);
        quotaGammaPerHour = c.getDouble("economy.player-market.quota.gamma-per-hour", 0.4);
        quotaGlobalCap = c.getDouble("economy.player-market.quota.global-cap", 4096.0);
        quotaPeriodHours = Math.max(1L, c.getLong("economy.player-market.quota.period-hours", 168L));

        epsilonWeekendFactor = c.getDouble("economy.player-market.indices.weekend-factor", 0.98);
        epsilonHolidayFactor = c.getDouble("economy.player-market.indices.holiday-factor", 0.95);
        epsilonNoiseStdDev = c.getDouble("economy.player-market.indices.noise-stddev", 0.02);
        epsilonMin = c.getDouble("economy.player-market.indices.epsilon-min", 0.85);
        epsilonMax = c.getDouble("economy.player-market.indices.epsilon-max", 1.10);

        decayDelta = c.getDouble("economy.player-market.decay.delta", 0.8);
        decayTauDays = c.getDouble("economy.player-market.decay.tau-days", 3.0);
        decayWindowDays = Math.max(1L, c.getLong("economy.player-market.decay.window-days", 21L));
        minSellMultiplier = c.getDouble("economy.player-market.decay.min-multiplier", 0.10);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isBlockedByQuota(UUID playerUuid, double amount) {
        if (!enabled || !quotaEnabled || amount <= 0.0) return false;
        pruneQuota(playerUuid);
        double quota = computeQuota(playerUuid);
        double used = getUsedQuota(playerUuid);
        return used + amount > quota;
    }

    public void recordSale(UUID playerUuid, String marketKey, int amount) {
        if (!enabled || amount <= 0) return;

        if (quotaEnabled) {
            long now = System.currentTimeMillis();
            quotaUsages
                    .computeIfAbsent(playerUuid, k -> new ConcurrentLinkedDeque<>())
                    .addLast(new QuotaUsage(now, amount));
            pruneQuota(playerUuid);
        }

        if (decayEnabled && marketKey != null && !marketKey.isBlank()) {
            long day = LocalDate.now(ZoneOffset.UTC).toEpochDay();
            PlayerItemKey key = new PlayerItemKey(playerUuid, marketKey.toLowerCase());
            soldBuckets
                    .computeIfAbsent(key, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(day, d -> new LongAdder())
                    .add(amount);
        }
    }

    public double computePersonalizedSellPrice(UUID playerUuid, String marketKey, double baseSellPrice, double itemLambda) {
        if (!enabled || playerUuid == null || marketKey == null || marketKey.isBlank()) return baseSellPrice;

        double decayMultiplier = 1.0;
        if (decayEnabled) {
            long nEff = computeEffectiveSold(playerUuid, marketKey);
            decayMultiplier = Math.exp(-Math.max(0.0, itemLambda) * nEff);
            decayMultiplier = clamp(decayMultiplier, minSellMultiplier, 1.0);
        }

        double epsilon = epsilonEnabled ? computeEnvironmentIndex(playerUuid, marketKey) : 1.0;
        double iota = specialEnabled ? computeSpecialIndex(marketKey) : 1.0;
        double finalPrice = baseSellPrice * decayMultiplier * epsilon * iota;
        return Math.max(0.01, finalPrice);
    }

    private double computeQuota(UUID playerUuid) {
        var snapshot = ActivityCollector.getSafeSnapshot(playerUuid);
        double hours = snapshot.playTimeSeconds() / 3600.0;
        return Math.min(quotaGlobalCap, quotaBase + (quotaGammaPerHour * hours));
    }

    private void pruneQuota(UUID playerUuid) {
        ConcurrentLinkedDeque<QuotaUsage> queue = quotaUsages.get(playerUuid);
        if (queue == null) return;
        long threshold = System.currentTimeMillis() - (quotaPeriodHours * 3600_000L);
        while (true) {
            QuotaUsage first = queue.peekFirst();
            if (first == null || first.timestampMs >= threshold) break;
            queue.pollFirst();
        }
    }

    private double getUsedQuota(UUID playerUuid) {
        ConcurrentLinkedDeque<QuotaUsage> queue = quotaUsages.get(playerUuid);
        if (queue == null) return 0.0;
        long threshold = System.currentTimeMillis() - (quotaPeriodHours * 3600_000L);
        double used = 0.0;
        for (QuotaUsage usage : queue) {
            if (usage.timestampMs >= threshold) used += usage.amount;
        }
        return used;
    }

    private long computeEffectiveSold(UUID playerUuid, String marketKey) {
        PlayerItemKey key = new PlayerItemKey(playerUuid, marketKey.toLowerCase());
        ConcurrentHashMap<Long, LongAdder> buckets = soldBuckets.get(key);
        if (buckets == null || buckets.isEmpty()) return 0L;

        long today = LocalDate.now(ZoneOffset.UTC).toEpochDay();
        long nEff = 0L;
        for (Map.Entry<Long, LongAdder> entry : buckets.entrySet()) {
            long day = entry.getKey();
            long ageDays = Math.max(0L, today - day);
            if (ageDays > decayWindowDays) continue;
            long sold = entry.getValue().sum();
            double weight = 1.0 / (Math.exp(decayDelta * (ageDays - decayTauDays)) + 1.0);
            nEff += (long) Math.floor(sold * weight);
        }
        return Math.max(0L, nEff);
    }

    private double computeEnvironmentIndex(UUID playerUuid, String marketKey) {
        Instant now = Instant.now();
        LocalDate date = now.atZone(ZoneOffset.UTC).toLocalDate();

        double base = 1.0;
        int dow = date.getDayOfWeek().getValue(); // 6,7 => weekend
        if (dow >= 6) base *= epsilonWeekendFactor;
        if (HolidayManager.isTodayHoliday()) base *= epsilonHolidayFactor;

        long day = ChronoUnit.DAYS.between(LocalDate.ofEpochDay(0), date);
        long seed = Objects.hash(day, playerUuid, marketKey.toLowerCase());
        Random random = new Random(seed);
        double noise = random.nextGaussian() * epsilonNoiseStdDev;

        return clamp(base + noise, epsilonMin, epsilonMax);
    }

    private double computeSpecialIndex(String marketKey) {
        String mk = marketKey.toLowerCase();
        String product = mk.contains(".") ? mk.substring(mk.lastIndexOf('.') + 1) : mk;
        FileConfiguration c = plugin.getConfig();

        double exact = c.getDouble("economy.player-market.indices.special-index." + mk, Double.NaN);
        if (Double.isFinite(exact)) return exact;

        return c.getDouble("economy.player-market.indices.special-index." + product, 1.0);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record QuotaUsage(long timestampMs, int amount) {}
    private record PlayerItemKey(UUID playerUuid, String marketKey) {}
}

