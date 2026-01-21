package top.ellan.ecobridge.integration.platform.hook;

import cn.superiormc.ultimateshop.api.ItemPreTransactionEvent;
import cn.superiormc.ultimateshop.api.ItemFinishTransactionEvent;
import cn.superiormc.ultimateshop.objects.buttons.ObjectItem;
import cn.superiormc.ultimateshop.objects.items.AbstractSingleThing;
import cn.superiormc.ultimateshop.objects.items.GiveResult;
import cn.superiormc.ultimateshop.objects.items.TakeResult;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.application.service.LimitManager;
import top.ellan.ecobridge.application.service.PricingManager;
import top.ellan.ecobridge.application.service.TransferManager;
import top.ellan.ecobridge.application.service.EconomicStateManager;
import top.ellan.ecobridge.infrastructure.ffi.model.NativeTransferResult;
import top.ellan.ecobridge.util.LogUtil;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Map;

/**
 * UltimateShopHook
 * 职责：
 * 1. 交易前执行动态限额审计与价格注入。
 * 2. 交易成功后触发物价反馈逻辑。
 */
public class UltimateShopHook implements Listener {

    private final TransferManager transferManager;
    private final PricingManager pricingManager;
    private final LimitManager limitManager;
    private final double sellRatio;

    public UltimateShopHook(TransferManager transferManager, PricingManager pricingManager, LimitManager limitManager) {
        this.transferManager = transferManager;
        this.pricingManager = pricingManager;
        this.limitManager = limitManager;
        this.sellRatio = EcoBridge.getInstance().getConfig().getDouble("economy.sell-ratio", 0.5);
    }

    @EventHandler
    public void onShopTransaction(ItemPreTransactionEvent event) {
        boolean isBuy = getReflectedBoolean(event, "isBuy");
        int amount = getReflectedInt(event, "multi");
        
        ObjectItem usItem = null;
        try {
            usItem = event.getItem();
        } catch (Throwable t) {
            usItem = (ObjectItem) getReflectedObject(event, "item");
        }

        Player player = event.getPlayer();
        if (usItem == null || player == null) return;

        String productId = usItem.getProduct();
        String uuidStr = player.getUniqueId().toString();

        if (isBuy) {
            if (limitManager.isBlockedByDynamicLimit(player.getUniqueId(), productId, amount)) {
                simulateCancellation(event, player, "动态限额不足 (需增加在线时长)");
                return;
            }

            double dynamicUnitPrice = pricingManager.getSnapshotPrice(uuidStr, productId);

            if (dynamicUnitPrice > 0) {
                double totalBasePrice = dynamicUnitPrice * amount;
                NativeTransferResult rustResult = transferManager.previewTransaction(player, totalBasePrice);

                if (rustResult.isBlocked()) {
                    simulateCancellation(event, player, "央行风控拦截 (Code: " + rustResult.warningCode() + ")");
                    return;
                }

                double finalTax = rustResult.finalTax();
                double finalCost = totalBasePrice + finalTax;

                TakeResult originalTake = event.getTakeResult();
                boolean modified = modifyMoneyInResult(originalTake.getResultMap(), finalCost);

                if (modified && finalTax > 0) {
                    String taxMsg = String.format("§7(基价: %.1f | §c调节税: %.1f§7)", totalBasePrice, finalTax);
                    player.sendActionBar(Component.text(taxMsg));
                }
            }
        } else { 
            if (limitManager.isBlockedBySellLimit(player.getUniqueId(), productId, amount)) {
                simulateCancellation(event, player, "商家收购额度已满 (单次上限)");
                return;
            }

            double dynamicUnitPrice = pricingManager.getSnapshotPrice(uuidStr, productId);

            if (dynamicUnitPrice > 0) {
                double finalPayout = dynamicUnitPrice * amount * sellRatio;
                GiveResult originalGive = event.getGiveResult();
                modifyMoneyInResult(originalGive.getResultMap(), finalPayout);
            }
        }
    }

    @EventHandler
    public void onShopTransactionFinish(ItemFinishTransactionEvent event) {
        ObjectItem usItem = null;
        try {
            usItem = event.getItem();
        } catch (Throwable t) {
            usItem = (ObjectItem) getReflectedObject(event, "item");
        }

        Player player = event.getPlayer();
        if (usItem == null || player == null) return;

        boolean isBuy = getReflectedBoolean(event, "isBuy");
        int amount = getReflectedInt(event, "multi");
        String productId = usItem.getProduct();

        if (isBuy) {
            EconomicStateManager.getInstance().recordPurchase(player, productId, amount);
        } else {
            EconomicStateManager.getInstance().recordSale(player, productId, amount);
        }
    }

    private boolean getReflectedBoolean(Object target, String fieldName) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.getBoolean(target);
        } catch (Exception e) {
            LogUtil.debug("UltimateShopHook 反射读取失败: " + fieldName);
            return true; 
        }
    }

    private int getReflectedInt(Object target, String fieldName) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.getInt(target);
        } catch (Exception e) {
            LogUtil.debug("UltimateShopHook 反射读取失败: " + fieldName);
            return 1; 
        }
    }
    
    private Object getReflectedObject(Object target, String fieldName) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(target);
        } catch (Exception e) {
            LogUtil.debug("UltimateShopHook 反射读取失败: " + fieldName);
            return null;
        }
    }

    private void simulateCancellation(ItemPreTransactionEvent event, Player player, String reason) {
        if (event.getTakeResult() != null && event.getTakeResult().getResultMap() != null) {
            event.getTakeResult().getResultMap().clear();
        }
        if (event.getGiveResult() != null && event.getGiveResult().getResultMap() != null) {
            event.getGiveResult().getResultMap().clear();
        }
        
        String color = "&c"; 
        try {
             ObjectItem item = event.getItem();
             if (item != null) color = limitManager.getMarketColor(item.getProduct());
        } catch (Exception ignored) {}

        player.sendMessage(color.replace('<','&').replace(">","") + "交易已拦截: " + reason);
        player.playSound(player.getLocation(), "entity.villager.no", 1f, 1f);
    }

    private boolean modifyMoneyInResult(Map<AbstractSingleThing, BigDecimal> resultMap, double newAmount) {
        if (resultMap == null) return false;
        
        boolean modified = false;
        for (Map.Entry<AbstractSingleThing, BigDecimal> entry : resultMap.entrySet()) {
            AbstractSingleThing thing = entry.getKey();
            ConfigurationSection section = thing.getSingleSection();

            if (section != null) {
                String ecoType = section.getString("economy-plugin", "");
                if (ecoType.equalsIgnoreCase("Vault") || 
                    ecoType.equalsIgnoreCase("PlayerPoints") || 
                    ecoType.equalsIgnoreCase("CMI") ||
                    ecoType.equalsIgnoreCase("CoinsEngine")) {
                    entry.setValue(BigDecimal.valueOf(newAmount));
                    modified = true;
                    break;
                }
            }
        }
        return modified;
    }
}