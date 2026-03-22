package top.ellan.ecobridge.integration.platform.asm;

import cn.superiormc.ultimateshop.objects.buttons.ObjectItem;
import cn.superiormc.ultimateshop.objects.items.prices.ObjectPrices;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.application.service.PricingManager;
import top.ellan.ecobridge.integration.platform.compat.UltimateShopCompat;
import top.ellan.ecobridge.util.LogUtil;

/**
 * ASMBridge
 *
 * <p>Replace UltimateShop economy price calculation with EcoBridge snapshot pricing.
 */
public class ASMBridge {

  private static final String UNKNOWN = "unknown";
  private static volatile Field cachedListField;

  public static ObjectPrices redirectPrice(
      ObjectItem item, ObjectPrices originalPrices, boolean isBuy) {
    try {
      if (item == null || originalPrices == null) return originalPrices;
      if (EcoBridge.getInstance().isShadowMode()) return originalPrices;

      String shopId = UltimateShopCompat.resolveShopId(item);
      String productId = UltimateShopCompat.resolveProductId(item);
      if (UNKNOWN.equals(productId)) return originalPrices;

      PricingManager pm = PricingManager.getInstance();
      if (pm == null) return originalPrices;

      double ecoPrice =
          isBuy
              ? pm.calculateBuyPrice(shopId, productId)
              : pm.calculateSellPrice(shopId, productId);

      if (!Double.isFinite(ecoPrice) || ecoPrice <= 0) {
        return originalPrices;
      }

      int replaced = overrideEconomyEntries(originalPrices, ecoPrice);
      if (replaced <= 0) {
        LogUtil.warnOnce(
            "asm-no-economy-price",
            "ASM price redirect active, but no economy price entry was found for "
                + shopId
                + "."
                + productId);
      }
      return originalPrices;

    } catch (Throwable t) {
      LogUtil.errorOnce("asm-price-bridge-err", "Price redirect failed: " + t.getMessage());
      return originalPrices;
    }
  }

  private static int overrideEconomyEntries(ObjectPrices prices, double newValue)
      throws IllegalAccessException {
    Collection<?> entries = readPriceEntries(prices);
    if (entries == null || entries.isEmpty()) return 0;

    int replaced = 0;
    for (Object entry : entries) {
      if (entry == null || !isEconomyEntry(entry)) continue;
      hardOverrideSinglePrice(entry, newValue);
      replaced++;
    }
    return replaced;
  }

  private static Collection<?> readPriceEntries(ObjectPrices prices) throws IllegalAccessException {
    Field field = cachedListField;
    if (field == null) {
      for (Field f : getAllFields(prices.getClass())) {
        if (Collection.class.isAssignableFrom(f.getType())
            || List.class.isAssignableFrom(f.getType())) {
          f.setAccessible(true);
          cachedListField = f;
          field = f;
          break;
        }
      }
    }
    if (field == null) return null;
    return (Collection<?>) field.get(prices);
  }

  private static boolean isEconomyEntry(Object priceObj) {
    Object type = readFieldValue(priceObj, "type");
    if (type != null) {
      String enumName = type.toString();
      if ("HOOK_ECONOMY".equals(enumName) || "VANILLA_ECONOMY".equals(enumName)) {
        return true;
      }
    }

    Object sectionObj = readFieldValue(priceObj, "singleSection");
    if (sectionObj instanceof ConfigurationSection section) {
      return section.contains("economy-plugin") || section.contains("economy-type");
    }

    return false;
  }

  private static void hardOverrideSinglePrice(Object singlePrice, double newValue) {
    overwriteNumericAndFormula(singlePrice, newValue);

    writeFieldValue(singlePrice, "isStatic", true);
    writeFieldValue(singlePrice, "amountOption", String.valueOf(newValue));
    writeFieldValue(singlePrice, "baseAmount", BigDecimal.valueOf(newValue));
    writeFieldValue(singlePrice, "applyCostMap", new HashMap<Integer, BigDecimal>());
  }

  private static void overwriteNumericAndFormula(Object target, double value) {
    for (Field f : getAllFields(target.getClass())) {
      try {
        f.setAccessible(true);
        Class<?> type = f.getType();
        String name = f.getName().toLowerCase(java.util.Locale.ROOT);

        if (type == double.class) {
          f.setDouble(target, value);
        } else if (type == Double.class) {
          f.set(target, value);
        } else if (type == BigDecimal.class) {
          f.set(target, BigDecimal.valueOf(value));
        } else if (type == String.class) {
          if (name.contains("amount")
              || name.contains("value")
              || name.contains("price")
              || name.contains("formula")
              || name.contains("text")
              || name.contains("parse")) {
            f.set(target, String.valueOf(value));
          }
        } else if (type.getSimpleName().contains("Number")) {
          Object numberObj = f.get(target);
          if (numberObj != null) {
            overwriteNumericAndFormula(numberObj, value);
          }
        }
      } catch (Throwable ignored) {
        // best-effort overwrite
      }
    }
  }

  private static Object readFieldValue(Object target, String fieldName) {
    Field f = findField(target.getClass(), fieldName);
    if (f == null) return null;
    try {
      f.setAccessible(true);
      return f.get(target);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static void writeFieldValue(Object target, String fieldName, Object value) {
    Field f = findField(target.getClass(), fieldName);
    if (f == null) return;
    try {
      f.setAccessible(true);
      f.set(target, value);
    } catch (Exception ignored) {
      // best-effort overwrite
    }
  }

  private static Field findField(Class<?> type, String fieldName) {
    Class<?> cursor = type;
    while (cursor != null && cursor != Object.class) {
      try {
        return cursor.getDeclaredField(fieldName);
      } catch (NoSuchFieldException ignored) {
        cursor = cursor.getSuperclass();
      }
    }
    return null;
  }

  private static List<Field> getAllFields(Class<?> type) {
    List<Field> result = new ArrayList<>();
    Class<?> cursor = type;
    while (cursor != null && cursor != Object.class) {
      for (Field f : cursor.getDeclaredFields()) {
        result.add(f);
      }
      cursor = cursor.getSuperclass();
    }
    return result;
  }

  public static int adjustLimit(ObjectItem item, int originalLimit, boolean isBuy) {
    return EcoBridge.getInstance().isShadowMode() ? originalLimit : -1;
  }
}
