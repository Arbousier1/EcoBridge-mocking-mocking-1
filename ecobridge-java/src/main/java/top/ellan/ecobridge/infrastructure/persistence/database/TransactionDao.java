package top.ellan.ecobridge.infrastructure.persistence.database;

import top.ellan.ecobridge.infrastructure.cache.HotDataCache;
import top.ellan.ecobridge.infrastructure.cache.HotDataCache.PlayerData;
import top.ellan.ecobridge.infrastructure.ffi.model.SaleRecord;
import top.ellan.ecobridge.util.LogUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Transaction Data Access Object (TransactionDao v0.8.12-Hotfix)
 * <p>
 * Responsibilities:
 * 1. SSoT (Single Source of Truth) retrieval and persistence for player assets.
 * 2. Handling optimistic locking concurrent writes.
 * 3. Providing historical transaction data for market analysis.
 * 4. Batch querying historical average prices for the pricing engine.
 */
public class TransactionDao {

    // ==================== Module A: Player Profile Access (SSoT) ====================

    /**
     * Load player data from the database (including balance and optimistic lock version).
     */
    public static PlayerData loadPlayerData(UUID uuid) {
        // Fail-fast check
        if (!DatabaseManager.isConnected()) {
            return new PlayerData(uuid, 0.0, 0);
        }

        String sql = "SELECT balance, version FROM ecobridge_players WHERE uuid = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, uuid.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return new PlayerData(uuid, rs.getDouble("balance"), rs.getLong("version"));
                }
            }
        } catch (SQLException e) {
            LogUtil.error("Failed to load player SQL data: " + uuid, e);
        }
        // Return default empty object if no record exists
        return new PlayerData(uuid, 0.0, 0);
    }

    /**
     * Update player balance asynchronously.
     */
    public static void updateBalance(UUID uuid, double balance) {
        if (DatabaseManager.getExecutor() == null) return;
        DatabaseManager.getExecutor().execute(() -> updateBalanceBlocking(uuid, balance));
    }

    /**
     * Synchronously persist player balance (with optimistic locking retry mechanism).
     * This method is thread-safe and handles concurrent write conflicts.
     */
    public static void updateBalanceBlocking(UUID uuid, double balance) {
        if (!DatabaseManager.isConnected()) return;

        // CAS Update: Only update if version matches, and increment version
        String updateSql = "UPDATE ecobridge_players SET balance = ?, version = version + 1, last_updated = ? WHERE uuid = ? AND version = ?";
        // Insert: Insert if not exists
        String insertSql = "INSERT IGNORE INTO ecobridge_players (uuid, balance, version, last_updated) VALUES (?, ?, 0, ?)";

        long now = System.currentTimeMillis();
        int maxRetries = 3; // Maximum retries
        SQLException lastEx = null;

        // [Fix Start] 新增变量：用于在重试循环中传递从数据库获取的正确版本号
        long nextVersionOverride = -2; 

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            // Get current version from cache
            PlayerData cached = HotDataCache.get(uuid);
            
            // [Fix Start] 优先使用 Override 版本（解决缓存为空时的版本丢失问题）
            long currentVersion;
            if (nextVersionOverride != -2) {
                currentVersion = nextVersionOverride;
            } else {
                currentVersion = (cached != null) ? cached.getVersion() : -1;
            }
            // [Fix End]

            try (Connection conn = DatabaseManager.getConnection()) {
                // 1. If no local version (or new player), try insert
                // [Fix] 增加检查：只有在没有 Override 版本时才尝试插入
                if (currentVersion == -1 && nextVersionOverride == -2) {
                    try (PreparedStatement ipstmt = conn.prepareStatement(insertSql)) {
                        ipstmt.setString(1, uuid.toString());
                        ipstmt.setDouble(2, balance);
                        ipstmt.setLong(3, now);
                        // If affected rows > 0, insert successful, return immediately
                        if (ipstmt.executeUpdate() > 0) return;
                    }
                }

                // 2. Execute CAS Update
                try (PreparedStatement upstmt = conn.prepareStatement(updateSql)) {
                    upstmt.setDouble(1, balance);
                    upstmt.setLong(2, now);
                    upstmt.setString(3, uuid.toString());
                    upstmt.setLong(4, Math.max(0, currentVersion));

                    int affected = upstmt.executeUpdate();
                    if (affected > 0) {
                        // Update successful, sync version in memory to avoid useless retries
                        if (cached != null) cached.setVersion(currentVersion + 1);
                        return;
                    }
                }

                // 3. Update failed (version mismatch, concurrent modification occurred)
                // Reload latest data from database
                PlayerData fresh = loadPlayerData(uuid);
                
                // [Fix Start] 关键修复：无论缓存是否存在，都保存最新版本号供下一次循环使用
                nextVersionOverride = fresh.getVersion();
                // [Fix End]
                
                if (cached != null) {
                    // [CRITICAL FIX] Sync BOTH Version AND Balance
                    // Without syncing balance, the next write might use the old balance, 
                    // causing a rollback of funds from other transactions.
                    cached.setVersion(fresh.getVersion());
                    cached.setBalance(fresh.getBalance()); 
                }

                // Simple exponential backoff
                if (attempt < maxRetries) {
                    Thread.sleep(50L * attempt);
                }

            } catch (SQLException e) {
                lastEx = e;
                if (isFatalError(e)) break; // Stop retrying on fatal errors (e.g., syntax)
                try { Thread.sleep(100L * attempt); } catch (InterruptedException ie) { break; }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        LogUtil.error("Failed to persist player balance (Optimistic Lock Conflict/Retries Exhausted): " + uuid, lastEx);
    }

    // ==================== Module B: Market Transaction Log Backup ====================

    /**
     * Asynchronously record transaction logs (for market analysis and backtracking).
     */
    public static void saveSaleAsync(UUID uuid, String productId, double amount) {
        if (DatabaseManager.getExecutor() == null) return;
        
        DatabaseManager.getExecutor().execute(() -> {
            String sql = "INSERT INTO ecobridge_sales(player_uuid, product_id, amount, timestamp) VALUES(?,?,?,?)";
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setString(1, uuid != null ? uuid.toString() : "SYSTEM");
                pstmt.setString(2, productId);
                pstmt.setDouble(3, amount);
                pstmt.setLong(4, System.currentTimeMillis());
                pstmt.executeUpdate();

            } catch (SQLException e) {
                LogUtil.error("Failed to write SQL transaction history: " + productId, e);
            }
        });
    }

    // ==================== Module C: Behavioral Economics Data Support ====================

    /**
     * Get average transaction volume (absolute value) for a specific product over the last 7 days.
     */
    public static double get7DayAverage(String productId) {
        if (!DatabaseManager.isConnected()) return 0.0;

        String sql = "SELECT AVG(ABS(amount)) FROM ecobridge_sales WHERE product_id = ? AND timestamp > ?";
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, productId);
            pstmt.setLong(2, cutoff);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble(1);
                }
            }
        } catch (SQLException e) {
            LogUtil.error("Sliding floor data backtrack error: " + productId, e);
        }
        return 0.0;
    }

    /**
     * Batch get average transaction volume (absolute value) for multiple products over the last 7 days.
     * * @param productIds List of product IDs to query
     * @return Map<ProductID, 7-Day Average Volume>
     */
    public static Map<String, Double> get7DayAveragesBatch(List<String> productIds) {
        // Fail-fast check
        if (productIds == null || productIds.isEmpty() || !DatabaseManager.isConnected()) {
            return new HashMap<>();
        }
        
        Map<String, Double> resultMap = new HashMap<>();
        // Initialize default value 0.0 for all product IDs
        for (String productId : productIds) {
            resultMap.put(productId, 0.0);
        }
        
        // Build placeholders for IN clause
        String placeholders = String.join(",", Collections.nCopies(productIds.size(), "?"));
        String sql = "SELECT product_id, AVG(ABS(amount)) as avg_amount FROM ecobridge_sales " +
                      "WHERE product_id IN (" + placeholders + ") " +
                      "AND timestamp > ? " +
                      "GROUP BY product_id";
        
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);
        
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            // Set product ID parameters
            for (int i = 0; i < productIds.size(); i++) {
                pstmt.setString(i + 1, productIds.get(i));
            }
            // Set time parameter
            pstmt.setLong(productIds.size() + 1, cutoff);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String productId = rs.getString("product_id");
                    double avgAmount = rs.getDouble("avg_amount");
                    resultMap.put(productId, avgAmount);
                }
            }
        } catch (SQLException e) {
            LogUtil.error("Batch historical average price query failed", e);
            // Fallback: return initialized default values (0.0)
        }
        
        return resultMap;
    }

    /**
     * Get historical transaction records for a product (for calculating Neff and volatility).
     */
    public static List<SaleRecord> getProductHistory(String productId, int daysLimit) {
        if (!DatabaseManager.isConnected()) return new ArrayList<>();

        List<SaleRecord> history = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(daysLimit);

        String sql = "SELECT timestamp, amount FROM ecobridge_sales WHERE product_id = ? AND timestamp > ? " +
                "ORDER BY timestamp DESC LIMIT 5000";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, productId);
            pstmt.setLong(2, cutoff);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    history.add(new SaleRecord(rs.getLong("timestamp"), rs.getDouble("amount")));
                }
            }
        } catch (SQLException e) {
            LogUtil.error("Product SQL cold data backtrack error: " + productId, e);
        }
        return history;
    }

    private static boolean isFatalError(SQLException e) {
        String state = e.getSQLState();
        if (state == null) return false;
        // 42xxx: Syntax error, 23xxx: Constraint violation
        return state.startsWith("42") || state.startsWith("23");
    }
}