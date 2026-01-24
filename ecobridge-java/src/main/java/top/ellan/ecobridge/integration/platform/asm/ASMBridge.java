package top.ellan.ecobridge.integration.platform.asm;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.application.service.PricingManager;
import top.ellan.ecobridge.util.LogUtil;

// 保持引用以通过编译
import cn.superiormc.ultimateshop.objects.buttons.ObjectItem;
import cn.superiormc.ultimateshop.objects.items.prices.ObjectPrices;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

/**
 * ASMBridge (v3.2 - Hardened Formula Override)
 * 职责：拦截并修改 UltimateShop 中的 Vault (金币) 价格。
 * 加固：强制覆盖内部公式字符串，防止 UltimateShop 重新计算回原配置价格。
 */
public class ASMBridge {

    private static volatile Field cachedListField = null;

    public static ObjectPrices redirectPrice(ObjectItem item, ObjectPrices originalPrices, boolean isBuy) {
        try {
            if (item == null || originalPrices == null) return originalPrices;

            // 1. 识别物品 ID
            String productId = resolveProductId(item);
            if ("unknown".equals(productId)) return originalPrices;
            productId = productId.toLowerCase();

            // 2. 获取 EcoBridge 演算价格
            PricingManager pm = PricingManager.getInstance();
            if (pm == null) return originalPrices;
            double ecoPrice = isBuy ? pm.calculateBuyPrice(productId) : pm.calculateSellPrice(productId);

            // 3. 影子模式审计
            if (EcoBridge.getInstance().isShadowMode()) {
                return originalPrices;
            }

            // 4. 执行精准注入
            modifyVaultOnly(originalPrices, ecoPrice);

            return originalPrices;

        } catch (Throwable t) {
            LogUtil.errorOnce("asm-vault-bridge-err", "Vault 价格适配失败: " + t.getMessage());
            return originalPrices;
        }
    }

    private static void modifyVaultOnly(ObjectPrices prices, double newValue) throws Exception {
        if (cachedListField == null) {
            for (Field f : prices.getClass().getDeclaredFields()) {
                if (Collection.class.isAssignableFrom(f.getType()) || List.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    cachedListField = f;
                    break;
                }
            }
        }

        if (cachedListField == null) return;

        Collection<?> priceList = (Collection<?>) cachedListField.get(prices);
        if (priceList == null) return;

        for (Object priceObj : priceList) {
            if (priceObj == null) continue;
            String className = priceObj.getClass().getSimpleName();
            
            // 仅针对 Vault 价格进行深度覆写
            if (className.contains("Vault")) {
                updateSinglePriceObject(priceObj, newValue);
            }
        }
    }

    private static void updateSinglePriceObject(Object priceObj, double newValue) throws Exception {
        Class<?> clazz = priceObj.getClass();
        
        // 遍历字段寻找数值容器 (ObjectNumber) 或直接的 value 字段
        for (Field f : clazz.getDeclaredFields()) {
            f.setAccessible(true);
            
            // 情况 A: 直接包含 value 字段 (旧版或简化版)
            if ((f.getType() == double.class || f.getType() == Double.class) && f.getName().equals("value")) {
                f.setDouble(priceObj, newValue);
            }
            // 情况 B: 包含 amount/price 等数值包装类 (通常叫 ObjectNumber)
            else if (f.getType().getSimpleName().contains("Number")) {
                Object numberObj = f.get(priceObj);
                if (numberObj != null) {
                    overwriteObjectNumber(numberObj, newValue);
                }
            }
        }
    }

    /**
     * 核心加固逻辑：不仅修改 double 值，还修改 String 公式
     */
    private static void overwriteObjectNumber(Object numberObj, double val) throws Exception {
        for (Field f : numberObj.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            
            // 1. 覆盖 double 值
            if (f.getType() == double.class || f.getType() == Double.class) {
                f.setDouble(numberObj, val);
            }
            // 2. 覆盖 String 公式 (防止重新解析)
            // UltimateShop 通常将配置的公式字符串存在 String 类型的字段中 (如 'text', 'parse')
            else if (f.getType() == String.class) {
                // 将公式 "10*(...)" 强制替换为静态字符串 "15.5"
                // 这样即使插件尝试重新解析公式，也只能解析出我们的静态值
                f.set(numberObj, String.valueOf(val));
            }
        }
    }

    private static String resolveProductId(ObjectItem item) {
        try {
            Method getConfig = item.getClass().getMethod("getItemConfig");
            Object config = getConfig.invoke(item);
            if (config instanceof ConfigurationSection section) {
                return section.getString("material", section.getString("type", "unknown"));
            }
        } catch (Exception ignored) {}

        try {
            Method getDisplay = item.getClass().getMethod("getDisplayItem", org.bukkit.entity.Player.class);
            ItemStack stack = (ItemStack) getDisplay.invoke(item, (Object) null);
            if (stack != null) return stack.getType().name();
        } catch (Exception ignored) {}

        return "unknown";
    }

    public static int adjustLimit(ObjectItem item, int originalLimit, boolean isBuy) {
        return EcoBridge.getInstance().isShadowMode() ? originalLimit : -1;
    }
}