package top.ellan.ecobridge.integration.platform.listener;

import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import su.nightexpress.coinsengine.api.currency.Currency;
import su.nightexpress.coinsengine.api.event.ChangeBalanceEvent;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.application.context.TransactionContext;
import top.ellan.ecobridge.application.service.EconomyManager;
import top.ellan.ecobridge.infrastructure.cache.HotDataCache;
import top.ellan.ecobridge.infrastructure.cache.HotDataCache.PlayerData;
import top.ellan.ecobridge.infrastructure.persistence.database.TransactionDao;
import top.ellan.ecobridge.infrastructure.persistence.storage.AsyncLogger;

/**
 * 缁忔祹浼犳劅鍣?(CoinsEngineListener v0.9.1 - Context Aware) 鑱岃矗锛氫綔涓哄簳灞備紶鎰熷櫒锛屽疄鏃舵崟鑾?CoinsEngine
 * 浣欓鍙樺姩淇″彿骞跺悓姝ヨ嚦鍏ㄦ湇缁忔祹绯荤粺銆? * 淇璁板綍锛? 1. [Fix] 寮曞叆 TransactionContext 瑙ｅ喅 M1 涓?Velocity
 * 鐨勬贩娣嗛棶棰樸€? 2. [Fix] 閫氳繃鍙粺璁¤礋鍚?Delta 瑙ｅ喅鍙岄噸璁℃暟 (Double Counting) 闂銆? 3. [Fix] 淇 PlayerData
 * 绫诲瀷寮曠敤鐨勭紪璇戦敊璇€?
 */
public class CoinsEngineListener implements Listener {

  private final String targetCurrencyId;
  private static final double EPSILON = 1e-6; // 杩囨护璁＄畻鑸嶅叆浜х敓鐨勫櫔澹?

  public CoinsEngineListener(EcoBridge plugin) {
    // 浠庨厤缃腑閿佸畾涓荤洃鎺ц揣甯?ID (濡?"coins")
    this.targetCurrencyId = plugin.getConfig().getString("economy.currency-id", "coins");
  }

  /** 瀹炴椂鐩戝惉浣欓鍙樺姩浜嬩欢 浼樺厛绾ц瀹氫负 MONITOR锛屼粎瑙傚療鎴愪氦缁撴灉锛岀‘淇濅笉骞叉秹鍏朵粬鎻掍欢鐨勪笟鍔￠€昏緫銆? */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBalanceChange(ChangeBalanceEvent event) {
    // 1. 璐у竵绫诲瀷杩囨护锛氫粎鐩戞帶鐩爣涓昏揣甯?
    Currency currency = event.getCurrency();
    if (!targetCurrencyId.equals(currency.getId())) {
      return;
    }

    // 2. 宸垎婕旂畻 (Delta Calculation)
    double oldAmount = event.getOldAmount();
    double newAmount = event.getNewAmount();
    double delta = newAmount - oldAmount;

    // 3. 鍣０杩囨护锛氬拷鐣ョ敱浜庢诞鐐圭簿搴︿骇鐢熺殑寰皬鎶栧姩
    if (Math.abs(delta) < EPSILON) {
      return;
    }

    // --- 鏍稿績淇锛氬熀浜庝笂涓嬫枃璇嗗埆鍙樺姩鏉ユ簮 ---
    // 妫€鏌ュ綋鍓嶇嚎绋嬫槸鍚︽寔鏈夆€滃競鍦轰氦鏄撯€濇爣璁?(鐢?TransferManager 璁剧疆)
    boolean isMarketTrade = TransactionContext.isMarketTrade();

    if (isMarketTrade) {
      // [Fix Double Counting]
      // 甯傚満浜ゆ槗锛堣浆璐︼級浼氫骇鐢熶袱绗斿彉鍔細A鎵ｆ锛孊鍏ヨ处銆?
      // 鎴戜滑鍙湪鎵ｆ鍙戠敓鏃?delta < 0)璁板綍涓€娆′氦鏄撻锛岄伩鍏嶇儹搴﹁櫄楂?2 鍊嶃€?
      if (delta < 0) {
        // 璁板綍浜ゆ槗鐑害 (Velocity)锛屼紶鍏?true 瑙﹀彂 PID 婕旂畻
        EconomyManager.getInstance().onTransaction(Math.abs(delta), true);
      }
    } else {
      // 闈?EcoBridge 瑙﹀彂鐨勫彉鍔紙濡傛帶鍒跺彴鎸囦护銆佸叾浠栨彃浠跺彂濂栥€佹瘡鏃ュ埄鎭瓑锛?
      // 瑙嗕负 M1 渚涘簲閲忓彉鍖栵紝涓嶈鍏ュ競鍦虹儹搴︼紝閬垮厤閿欒鎷夐珮鐗╀环銆?
      EconomyManager.getInstance().onTransaction(delta, false);
    }

    // 4. [SSoT 淇]: 瑙﹀彂鏁版嵁鍚屾涓庡璁?
    // 娉ㄦ剰锛欳oinsEngine 鐨?getUser().getId() 閫氬父杩斿洖 UUID
    syncToStorage(event.getUser().getId(), newAmount, delta, isMarketTrade);
  }

  private void syncToStorage(UUID uuid, double balance, double delta, boolean isMarketTrade) {
    // 1. 鍚屾鐑暟鎹紦瀛?(HotDataCache)
    // 鏄惧紡浣跨敤 PlayerData 绫诲瀷锛岃В鍐充箣鍓嶇殑 Object 绫诲瀷鎶ラ敊
    PlayerData cachedData = HotDataCache.get(uuid);
    if (cachedData != null) {
      cachedData.updateFromTruth(balance);
    }

    // 2. [SSoT 淇]: 寮傛鎸佷箙鍖栧揩鐓?
    // 鍦ㄦ暟鎹簱涓洿鏂拌鐜╁鐨勬渶缁堜綑棰濆揩鐓?
    TransactionDao.updateBalance(uuid, balance);

    // 3. 瀹¤灞傦細瑙﹀彂寮傛鎸佷箙鍖栨棩蹇?(AsyncLogger)
    // 鏍规嵁涓婁笅鏂囨爣璁?Meta锛屾柟渚?DuckDB 鍒嗘瀽璧勯噾鏉ユ簮
    String meta = isMarketTrade ? "INTERNAL_MARKET_TRADE" : "EXTERNAL_API_CHANGE";

    AsyncLogger.log(
        uuid,
        delta, // 浜ゆ槗鍙樺姩鍑€鍊?
        balance, // 浜ゆ槗鍚庝綑棰濆揩鐓?
        System.currentTimeMillis(),
        meta // [Fix] 鍔ㄦ€?Meta 鏍囩
        );
  }
}
