package top.ellan.ecobridge.infrastructure.persistence.transaction;

import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.infrastructure.persistence.database.DatabaseManager;
import top.ellan.ecobridge.util.LogUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * 自动对账与自愈任务 (v1.2.0)
 * 职责：
 * 1. 定期扫描僵尸事务（Pending 状态超时的记录）。
 * 2. 自动执行“逻辑回滚”，将过期事务标记为 FAILED，防止数据污染。
 * 3. 记录自动化修复日志，实现金融自愈闭环。
 */
public class ReconciliationTask implements Runnable {

    // 扫描超过 5 分钟仍未提交的事务
    private static final long TIMEOUT_MS = 300_000L; 

    public static void start(EcoBridge plugin) {
        // 延迟 1 分钟启动，每 10 分钟执行一次
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, 
            new ReconciliationTask(), 1200L, 12000L);
    }

    @Override
    public void run() {
        if (!DatabaseManager.isConnected()) return;

        LogUtil.debug("开始执行自动化对账巡检...");
        long threshold = System.currentTimeMillis() - TIMEOUT_MS;
        
        // 1. 查询所有超时的 Pending 事务
        String selectSql = "SELECT tx_id, sender_uuid, amount FROM ecobridge_journal WHERE state = ? AND created_at < ?";
        List<String> expiredTxIds = new ArrayList<>();

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement selectStmt = conn.prepareStatement(selectSql)) {
            
            selectStmt.setInt(1, TransactionJournal.STATE_PENDING);
            selectStmt.setLong(2, threshold);

            try (ResultSet rs = selectStmt.executeQuery()) {
                while (rs.next()) {
                    String txId = rs.getString("tx_id");
                    expiredTxIds.add(txId);
                    
                    // 记录修复前的详细快照
                    LogUtil.warn("检测到悬挂事务: " + txId + " | 发起人: " + rs.getString("sender_uuid") + " | 金额: " + rs.getDouble("amount"));
                }
            }

            // 2. 执行自动化自愈修复
            if (!expiredTxIds.isEmpty()) {
                repairTransactions(conn, expiredTxIds);
            }

        } catch (Exception e) {
            LogUtil.error("自动对账自愈任务执行异常", e);
        }
    }

    /**
     * 自动化修复逻辑：
     * 将所有 Pending 的僵尸事务批量标记为 FAILED (状态 2)
     * 这样可以释放该笔交易在日志中的占用，并明确标记为“未完成”
     */
    private void repairTransactions(Connection conn, List<String> txIds) {
        String updateSql = "UPDATE ecobridge_journal SET state = ?, metadata = ? WHERE tx_id = ?";
        
        try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
            conn.setAutoCommit(false); // 开启批处理事务

            for (String txId : txIds) {
                updateStmt.setInt(1, TransactionJournal.STATE_ROLLED_BACK);
                updateStmt.setString(2, "SYSTEM_AUTO_REPAIR_TIMEOUT");
                updateStmt.setString(3, txId);
                updateStmt.addBatch();
            }

            int[] results = updateStmt.executeBatch();
            conn.commit();
            
            LogUtil.info("✔ 自动化自愈完成：成功修复 " + results.length + " 笔悬挂事务，状态已重置为 FAILED。");
        } catch (Exception e) {
            try { conn.rollback(); } catch (Exception ignored) {}
            LogUtil.severe("❌ 尝试自动化修复悬挂事务时发生崩溃", e);
        }
    }
}