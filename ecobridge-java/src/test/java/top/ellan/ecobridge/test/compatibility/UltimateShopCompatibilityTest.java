package top.ellan.ecobridge.test.compatibility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * UltimateShop compatibility check.
 * Ensures our ASM hook targets still exist in upstream releases.
 */
public class UltimateShopCompatibilityTest {

    private static final String TARGET_CLASS = "cn.superiormc.ultimateshop.objects.buttons.ObjectItem";
    private static final String HOOK_METHOD_BUY = "getBuyPrice";
    private static final String HOOK_METHOD_SELL = "getSellPrice";
    private static final String PRICES_CLASS = "cn.superiormc.ultimateshop.objects.items.prices.ObjectPrices";

    @Test
    @DisplayName("Check UltimateShop method signatures for ASM hooks")
    public void checkMethodSignatures() throws Exception {
        String jarPathStr = System.getProperty("ultimateshop.jar.path", "libs/UltimateShop.jar");
        Path jarPath = Paths.get(jarPathStr);

        if (!Files.exists(jarPath)) {
            System.out.println("UltimateShop.jar not found, skip compatibility check in local environment.");
            return;
        }

        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{jarPath.toUri().toURL()},
                this.getClass().getClassLoader()
        )) {
            Class<?> targetClass = Class.forName(TARGET_CLASS, false, loader);
            System.out.println("Loaded target class: " + targetClass.getName());

            Method buyMethod = assertDoesNotThrow(() -> targetClass.getDeclaredMethod(HOOK_METHOD_BUY),
                    "Missing method: " + HOOK_METHOD_BUY);
            assertEquals(PRICES_CLASS, buyMethod.getReturnType().getName(),
                    "Return type changed: " + HOOK_METHOD_BUY);
            System.out.println(HOOK_METHOD_BUY + " descriptor: " + Type.getMethodDescriptor(buyMethod));

            Method sellMethod = assertDoesNotThrow(() -> targetClass.getDeclaredMethod(HOOK_METHOD_SELL),
                    "Missing method: " + HOOK_METHOD_SELL);
            assertEquals(PRICES_CLASS, sellMethod.getReturnType().getName(),
                    "Return type changed: " + HOOK_METHOD_SELL);
            System.out.println(HOOK_METHOD_SELL + " descriptor: " + Type.getMethodDescriptor(sellMethod));
        }
    }
}
