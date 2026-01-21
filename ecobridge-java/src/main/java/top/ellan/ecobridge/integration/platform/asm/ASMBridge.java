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
 * ASMBridge (v3.1 - Vault Specific Adapter)
 * 职责：仅拦截并修改 UltimateShop 中的 Vault (金币) 价格，保留其他自定义货币。
 */
public class ASMBridge {

    private static volatile Field cachedListField = null;
    private static volatile Field cachedValueField = null;

    /**
     * 核心逻辑：重定向复合价格中的金币部分
     */
    public static ObjectPrices redirectPrice(ObjectItem item, ObjectPrices originalPrices, boolean isBuy) {
        try {
            if (item == null || originalPrices == null) return originalPrices;

            // 1. 识别物品 ID
            String productId = resolveProductId(item);
            if ("unknown".equals(productId)) return originalPrices;
            productId = productId.toLowerCase();

            // 2. 获取 EcoBridge 演算价格 (仅针对主货币金币)
            PricingManager pm = PricingManager.getInstance();
            if (pm == null) return originalPrices;
            double ecoPrice = isBuy ? pm.calculateBuyPrice(productId) : pm.calculateSellPrice(productId);

            // 3. 影子模式审计
            if (EcoBridge.getInstance().isShadowMode()) {
                // 审计逻辑...
                return originalPrices;
            }

            // 4. 执行精准注入：仅修改 Vault 类型的价格条目
            modifyVaultOnly(originalPrices, ecoPrice);

            return originalPrices;

        } catch (Throwable t) {
            LogUtil.errorOnce("asm-vault-bridge-err", "Vault 价格适配失败: " + t.getMessage());
            return originalPrices;
        }
    }

    /**
     * 深度遍历并过滤 Vault 价格条目
     */
    private static void modifyVaultOnly(ObjectPrices prices, double newValue) throws Exception {
        // 定位内部 List<ObjectPrice>
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

            // --- 核心修复：类型过滤 ---
            // UltimateShop 内部实现类通常叫 VaultPrice 或类似名称
            String className = priceObj.getClass().getSimpleName();
            
            if (className.contains("Vault")) {
                // 只有金币条目才会被 EcoBridge 的 PID 价格覆盖
                updateSinglePriceObject(priceObj, newValue);
                // LogUtil.debug("已同步 Vault 价格: " + newValue);
            } else {
                // 其他条目（如 PlayerPoints, ItemPrice）保持原样
                // LogUtil.debug("忽略非 Vault 货币条目: " + className);
            }
        }
    }

    /**
     * 反射改写单个 ObjectPrice 内部的数值
     */
    private static void updateSinglePriceObject(Object priceObj, double newValue) throws Exception {
        // 探测该对象内部存储数值的字段
        if (cachedValueField == null) {
            Class<?> clazz = priceObj.getClass();
            for (Field f : clazz.getDeclaredFields()) {
                // 适配 double 字段或 ObjectNumber 包装类
                if (f.getType() == double.class || f.getType() == Double.class || f.getName().equals("value")) {
                    f.setAccessible(true);
                    cachedValueField = f;
                    break;
                }
                // 如果发现内部嵌套了数值包装类 (ObjectNumber)
                if (f.getType().getSimpleName().contains("Number")) {
                    f.setAccessible(true);
                    Object numberObj = f.get(priceObj);
                    if (numberObj != null) {
                        updateSingleValue(numberObj, newValue);
                        return;
                    }
                }
            }
        }

        if (cachedValueField != null) {
            cachedValueField.setDouble(priceObj, newValue);
        }
    }

    private static void updateSingleValue(Object obj, double val) throws Exception {
        // 递归进入数值包装对象修改真正的 double value
        for (Field f : obj.getClass().getDeclaredFields()) {
            if (f.getType() == double.class || f.getType() == Double.class) {
                f.setAccessible(true);
                f.setDouble(obj, val);
                return;
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