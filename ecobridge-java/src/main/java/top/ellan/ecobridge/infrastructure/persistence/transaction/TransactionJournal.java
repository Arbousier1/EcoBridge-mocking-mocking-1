package top.ellan.ecobridge.infrastructure.persistence.transaction;

import top.ellan.ecobridge.infrastructure.persistence.database.DatabaseManager;
import top.ellan.ecobridge.util.LogUtil;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

/**
 * 预写式日志 (Write-Ahead Log) 管理器
 * 确保在执行任何 CoinsEngine 操作前，意图已落盘
 */
public class TransactionJournal {

    private static final String SQL_INIT = """
        CREATE TABLE IF NOT EXISTS ecobridge_journal (
            tx_id CHAR(36) PRIMARY KEY,
            sender_uuid CHAR(36),
            receiver_uuid CHAR(36),
            amount DOUBLE,
            tax DOUBLE,
            state TINYINT, -- 0: PENDING, 1: COMMITTED, 2: ROLLED_BACK
            created_at BIGINT,
            updated_at BIGINT
        );
    """;

    // 状态常量
    public static final int STATE_PENDING = 0;
    public static final int STATE_COMMITTED = 1;
    public static final int STATE_ROLLED_BACK = 2;

    public static void init() {
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.createStatement().execute(SQL_INIT);
        } catch (Exception e) {
            LogUtil.severe("致命: 无法初始化事务日志表，插件将无法安全运行！");
            throw new RuntimeException(e);
        }
    }

    /**
     * 第一步：开启事务 (写入磁盘)
     * 这必须在任何扣款操作之前发生
     */
    public static String beginTransaction(UUID sender, UUID receiver, double amount, double tax) {
        String txId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO ecobridge_journal VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            stmt.setString(1, txId);
            stmt.setString(2, sender.toString());
            stmt.setString(3, receiver != null ? receiver.toString() : "SYSTEM");
            stmt.setDouble(4, amount);
            stmt.setDouble(5, tax);
            stmt.setInt(6, STATE_PENDING);
            stmt.setLong(7, now);
            stmt.setLong(8, now);
            stmt.executeUpdate();
            return txId;
        } catch (Exception e) {
            LogUtil.error("WAL 写入失败，交易已阻断", e);
            throw new RuntimeException("WAL_WRITE_FAILURE");
        }
    }

    /**
     * 最终步：提交事务 (标记成功)
     */
    public static void commitTransaction(String txId) {
        updateState(txId, STATE_COMMITTED);
    }

    /**
     * 异常步：标记回滚 (供补偿任务扫描)
     */
    public static void markForRollback(String txId) {
        updateState(txId, STATE_ROLLED_BACK);
    }

    private static void updateState(String txId, int state) {
        DatabaseManager.getExecutor().execute(() -> {
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE ecobridge_journal SET state = ?, updated_at = ? WHERE tx_id = ?")) {
                stmt.setInt(1, state);
                stmt.setLong(2, System.currentTimeMillis());
                stmt.setString(3, txId);
                stmt.executeUpdate();
            } catch (Exception e) {
                LogUtil.error("事务状态更新失败: " + txId, e);
            }
        });
    }
}