package top.ellan.ecobridge.integration.platform.hook;

import cn.superiormc.ultimateshop.api.ItemFinishTransactionEvent;
import cn.superiormc.ultimateshop.api.ItemPreTransactionEvent;
import cn.superiormc.ultimateshop.objects.buttons.ObjectItem;
import cn.superiormc.ultimateshop.objects.items.AbstractSingleThing;
import cn.superiormc.ultimateshop.objects.items.GiveResult;
import cn.superiormc.ultimateshop.objects.items.TakeResult;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import top.ellan.ecobridge.application.service.EconomicStateManager;
import top.ellan.ecobridge.application.service.LimitManager;
import top.ellan.ecobridge.application.service.PlayerMarketPolicyService;
import top.ellan.ecobridge.application.service.PricingManager;
import top.ellan.ecobridge.application.service.TransferManager;
import top.ellan.ecobridge.infrastructure.ffi.model.NativeTransferResult;
import top.ellan.ecobridge.integration.platform.compat.UltimateShopCompat;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Runtime hook for UltimateShop transaction events.
 */
public class UltimateShopHook implements Listener {

    private final TransferManager transferManager;
    private final PricingManager pricingManager;
    private final LimitManager limitManager;

    public UltimateShopHook(TransferManager transferManager, PricingManager pricingManager, LimitManager limitManager) {
        this.transferManager = transferManager;
        this.pricingManager = pricingManager;
        this.limitManager = limitManager;
    }

    @EventHandler
    public void onShopTransaction(ItemPreTransactionEvent event) {
        boolean isBuy = UltimateShopCompat.resolveBuyFlag(event);
        int amount = UltimateShopCompat.resolveAmount(event);

        ObjectItem item = resolveItemFromEvent(event);
        Player player = event.getPlayer();
        if (item == null || player == null) return;

        String productId = item.getProduct();
        String shopId = item.getShop();

        if (isBuy) {
            processBuy(event, player, shopId, productId, amount);
        } else {
            processSell(event, player, shopId, productId, amount);
        }
    }

    @EventHandler
    public void onShopTransactionFinish(ItemFinishTransactionEvent event) {
        ObjectItem item = resolveItemFromEvent(event);
        Player player = event.getPlayer();
        if (item == null || player == null) return;

        boolean isBuy = UltimateShopCompat.resolveBuyFlag(event);
        int amount = UltimateShopCompat.resolveAmount(event);
        String productId = item.getProduct();
        String shopId = item.getShop();

        if (isBuy) {
            EconomicStateManager.getInstance().recordPurchase(player, shopId, productId, amount);
        } else {
            EconomicStateManager.getInstance().recordSale(player, shopId, productId, amount);
            PlayerMarketPolicyService policy = PlayerMarketPolicyService.getInstance();
            if (policy != null) {
                policy.recordSale(player.getUniqueId(), PricingManager.toMarketKey(shopId, productId), amount);
            }
        }
    }

    private void processBuy(ItemPreTransactionEvent event, Player player, String shopId, String productId, int amount) {
        if (limitManager.isBlockedByDynamicLimit(player.getUniqueId(), productId, amount)) {
            simulateCancellation(event, player, "Dynamic buy limit reached");
            return;
        }

        double dynamicUnitPrice = pricingManager.calculateBuyPrice(shopId, productId);
        if (dynamicUnitPrice <= 0) return;

        double totalBasePrice = dynamicUnitPrice * amount;
        NativeTransferResult rustResult = transferManager.previewTransaction(player, totalBasePrice);

        if (rustResult.isBlocked()) {
            simulateCancellation(event, player, "Transfer blocked by regulator (Code: " + rustResult.warningCode() + ")");
            return;
        }

        double finalTax = rustResult.finalTax();
        double finalCost = totalBasePrice + finalTax;

        TakeResult originalTake = event.getTakeResult();
        boolean modified = modifyMoneyInResult(originalTake.getResultMap(), finalCost);

        if (modified && finalTax > 0) {
            String taxMsg = String.format("(Base: %.1f | Tax: %.1f)", totalBasePrice, finalTax);
            player.sendActionBar(Component.text(taxMsg));
        }
    }

    private void processSell(ItemPreTransactionEvent event, Player player, String shopId, String productId, int amount) {
        if (limitManager.isBlockedBySellLimit(player.getUniqueId(), productId, amount)) {
            simulateCancellation(event, player, "Dynamic sell limit reached");
            return;
        }

        if (limitManager.isBlockedByPlayerQuota(player.getUniqueId(), shopId, productId, amount)) {
            simulateCancellation(event, player, "Player quota pool exhausted");
            return;
        }

        double dynamicUnitPrice = pricingManager.calculateSellPriceForPlayer(player.getUniqueId(), shopId, productId);
        if (dynamicUnitPrice <= 0) return;

        double finalPayout = dynamicUnitPrice * amount;
        GiveResult originalGive = event.getGiveResult();
        modifyMoneyInResult(originalGive.getResultMap(), finalPayout);
    }

    private ObjectItem resolveItemFromEvent(Object event) {
        try {
            Object direct = event.getClass().getMethod("getItem").invoke(event);
            if (direct instanceof ObjectItem objectItem) return objectItem;
        } catch (Exception ignored) {
        }

        try {
            Field field = event.getClass().getDeclaredField("item");
            field.setAccessible(true);
            Object reflected = field.get(event);
            if (reflected instanceof ObjectItem objectItem) return objectItem;
        } catch (Exception ignored) {
        }

        return null;
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
        } catch (Exception ignored) {
        }

        player.sendMessage(color.replace('<', '&').replace(">", "") + "Transaction blocked: " + reason);
        player.playSound(player.getLocation(), "entity.villager.no", 1f, 1f);
    }

    private boolean modifyMoneyInResult(Map<AbstractSingleThing, BigDecimal> resultMap, double newAmount) {
        if (resultMap == null) return false;

        for (Map.Entry<AbstractSingleThing, BigDecimal> entry : resultMap.entrySet()) {
            AbstractSingleThing thing = entry.getKey();
            ConfigurationSection section = thing.getSingleSection();

            if (section == null) continue;
            String ecoType = section.getString("economy-plugin", "");
            if (ecoType.equalsIgnoreCase("Vault")
                    || ecoType.equalsIgnoreCase("PlayerPoints")
                    || ecoType.equalsIgnoreCase("CMI")
                    || ecoType.equalsIgnoreCase("CoinsEngine")) {
                entry.setValue(BigDecimal.valueOf(newAmount));
                return true;
            }
        }
        return false;
    }
}
