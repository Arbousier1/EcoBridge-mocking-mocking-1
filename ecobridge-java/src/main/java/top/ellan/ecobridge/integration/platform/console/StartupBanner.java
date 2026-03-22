package top.ellan.ecobridge.integration.platform.console;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.infrastructure.ffi.bridge.NativeBridge;
import top.ellan.ecobridge.infrastructure.i18n.I18n;

/** Console banner renderer for startup and reload summaries. */
public final class StartupBanner {

  private static final MiniMessage MM = MiniMessage.miniMessage();

  private StartupBanner() {}

  public static void print(EcoBridge plugin) {
    String version = plugin.getPluginMeta().getVersion();

    List<String> lines = new ArrayList<>();
    lines.add(I18n.tr("banner.title", version));
    lines.add(
        I18n.tr(
            "banner.native",
            NativeBridge.isLoaded()
                ? I18n.tr("banner.native.active")
                : I18n.tr("banner.native.disabled")));
    lines.add(
        I18n.tr(
            "banner.mode",
            plugin.isShadowMode() ? I18n.tr("banner.mode.shadow") : I18n.tr("banner.mode.enforced")));
    lines.add(I18n.tr("banner.concurrency"));
    lines.add(I18n.tr("banner.compat"));

    String borderGradient = "<gradient:aqua:blue>";
    String textGradient = "<gradient:white:gray>";
    int boxWidth = 55;

    sendConsole(borderGradient + buildBorder("+", "-", "+", boxWidth) + "</gradient>");
    for (String line : lines) {
      String centeredLine = centerText(line, boxWidth - 4);
      sendConsole(
          borderGradient
              + "|<reset>"
              + textGradient
              + centeredLine
              + "</gradient>"
              + borderGradient
              + "|</gradient>");
    }
    sendConsole(borderGradient + buildBorder("+", "-", "+", boxWidth) + "</gradient>");
  }

  private static void sendConsole(String msg) {
    Bukkit.getConsoleSender().sendMessage(MM.deserialize(msg));
  }

  private static String buildBorder(String left, String mid, String right, int width) {
    return left + mid.repeat(width - 2) + right;
  }

  private static String centerText(String text, int width) {
    int textVisualLength = getVisualLength(text);
    if (textVisualLength >= width) return text;
    int padding = width - textVisualLength;
    int leftPad = padding / 2;
    int rightPad = padding - leftPad;
    return " ".repeat(leftPad) + text + " ".repeat(rightPad);
  }

  private static int getVisualLength(String s) {
    if (s == null) return 0;
    int length = 0;
    for (char c : s.toCharArray()) {
      length += (c > 127) ? 2 : 1;
    }
    return length;
  }
}
