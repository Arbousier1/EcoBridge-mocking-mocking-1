package top.ellan.ecobridge.integration.platform.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import top.ellan.ecobridge.util.LogUtil;

/** Centralized compatibility resolver for UltimateShop API drift. */
public final class UltimateShopCompat {

  private static final String UNKNOWN = "unknown";

  private UltimateShopCompat() {}

  public static boolean resolveBuyFlag(Object event) {
    Object byMethod = invokeNoArg(event, "isBuyOrSell");
    if (byMethod instanceof Boolean value) return value;

    byMethod = invokeNoArg(event, "isBuy");
    if (byMethod instanceof Boolean value) return value;

    Object byField = getFieldValue(event, "buyOrSell");
    if (byField instanceof Boolean value) return value;

    byField = getFieldValue(event, "isBuy");
    if (byField instanceof Boolean value) return value;

    return true;
  }

  public static int resolveAmount(Object event) {
    Object byMethod = invokeNoArg(event, "getAmount");
    if (byMethod instanceof Integer value) return value;

    Object byField = getFieldValue(event, "amount");
    if (byField instanceof Integer value) return value;

    byField = getFieldValue(event, "multi");
    if (byField instanceof Integer value) return value;

    return 1;
  }

  public static String resolveShopId(Object item) {
    String byMethod = normalizeString(invokeNoArg(item, "getShop"));
    if (!UNKNOWN.equals(byMethod)) return byMethod;

    Object shopObj = invokeNoArg(item, "getShopObject");
    String byShopMethod = normalizeString(invokeNoArg(shopObj, "getShopName"));
    if (!UNKNOWN.equals(byShopMethod)) return byShopMethod;

    return UNKNOWN;
  }

  public static String resolveProductId(Object item) {
    String byMethod = normalizeString(invokeNoArg(item, "getProduct"));
    if (!UNKNOWN.equals(byMethod)) return byMethod;

    Object configObj = invokeNoArg(item, "getItemConfig");
    if (configObj instanceof ConfigurationSection section) {
      String sectionName = normalizeString(section.getName());
      if (!UNKNOWN.equals(sectionName)) return sectionName;
    }

    Object displayObj =
        invokeMethod(
            item,
            "getDisplayItem",
            new Class<?>[] {org.bukkit.entity.Player.class},
            new Object[] {null});
    if (displayObj instanceof ItemStack stack && stack.getType() != null) {
      return normalizeString(stack.getType().name());
    }

    return UNKNOWN;
  }

  public static String normalizeString(Object value) {
    if (value == null) return UNKNOWN;
    String text = value.toString().trim();
    if (text.isEmpty()) return UNKNOWN;
    return text.toLowerCase(Locale.ROOT);
  }

  private static Object invokeNoArg(Object target, String methodName) {
    if (target == null) return null;
    try {
      Method method = target.getClass().getMethod(methodName);
      return method.invoke(target);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static Object invokeMethod(
      Object target, String methodName, Class<?>[] argsTypes, Object[] args) {
    if (target == null) return null;
    try {
      Method method = target.getClass().getMethod(methodName, argsTypes);
      return method.invoke(target, args);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static Object getFieldValue(Object target, String fieldName) {
    if (target == null) return null;
    try {
      Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      return field.get(target);
    } catch (Exception e) {
      LogUtil.debug("UltimateShopCompat field read failed: " + fieldName);
      return null;
    }
  }
}
