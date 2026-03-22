package top.ellan.ecobridge.infrastructure.i18n;

import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import org.bukkit.configuration.file.FileConfiguration;
import top.ellan.ecobridge.EcoBridge;

/** Lightweight i18n accessor backed by ResourceBundle. */
public final class I18n {

  private static final String BUNDLE_BASE = "i18n.messages";
  private static volatile ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE_BASE, Locale.ENGLISH);
  private static volatile Locale currentLocale = Locale.ENGLISH;

  private I18n() {}

  public static void init(EcoBridge plugin) {
    reload(plugin);
  }

  public static void reload(EcoBridge plugin) {
    String tag = "zh-CN";
    try {
      FileConfiguration cfg = plugin.getConfig();
      tag = cfg.getString("i18n.locale", "zh-CN");
    } catch (Exception ignored) {
      // keep default
    }
    currentLocale = normalizeLocale(tag);
    bundle = ResourceBundle.getBundle(BUNDLE_BASE, currentLocale);
  }

  public static String tr(String key, Object... args) {
    String pattern = key;
    try {
      pattern = bundle.getString(key);
    } catch (MissingResourceException ignored) {
      // fallback to key
    }
    return MessageFormat.format(pattern, args);
  }

  public static Locale locale() {
    return currentLocale;
  }

  private static Locale normalizeLocale(String tag) {
    if (tag == null || tag.isBlank()) {
      return Locale.ENGLISH;
    }
    String normalized = tag.trim().replace('_', '-');
    Locale locale = Locale.forLanguageTag(normalized);
    if (locale.getLanguage().isBlank()) {
      return Locale.ENGLISH;
    }

    Map<String, Locale> aliases = new HashMap<>();
    aliases.put("zh", Locale.SIMPLIFIED_CHINESE);
    aliases.put("zh-cn", Locale.SIMPLIFIED_CHINESE);
    aliases.put("en", Locale.ENGLISH);
    aliases.put("en-us", Locale.ENGLISH);
    return aliases.getOrDefault(normalized.toLowerCase(Locale.ROOT), locale);
  }
}
