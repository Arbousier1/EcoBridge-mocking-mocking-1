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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上游兼容性哨兵测试
 * 目的：在 CI 阶段检测 UltimateShop 的核心 API 签名是否发生变更，防止 ASM 注入失效。
 */
public class UltimateShopCompatibilityTest {

    // 目标类全限定名 (请根据 UltimateShop 实际代码确认)
    // 假设是: uc.manyou.ultimateshop.api.ShopManager 或者具体的实现类
    private static final String TARGET_CLASS = "uc.manyou.ultimateshop.api.interfaces.ShopInterface"; 
    
    // 我们 ASM 注入钩住的方法名
    private static final String HOOK_METHOD_BUY = "getBuyPrice";
    private static final String HOOK_METHOD_SELL = "getSellPrice";

    @Test
    @DisplayName("检测 UltimateShop 关键方法签名有效性")
    public void checkMethodSignatures() throws Exception {
        // 1. 在 CI 环境中寻找 UltimateShop 的构建产物
        // 下面的路径需要与 GitHub Actions 中的 Artifact 路径对应
        String jarPathStr = System.getProperty("ultimateshop.jar.path", "libs/UltimateShop.jar");
        Path jarPath = Paths.get(jarPathStr);

        if (!Files.exists(jarPath)) {
            // 如果是本地开发环境没下载 jar，可以选择跳过或警告
            System.out.println("⚠️ 未找到 UltimateShop.jar，跳过兼容性检查 (仅在 CI 环境强制执行)");
            return;
        }

        // 2. 动态加载 JAR
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{jarPath.toUri().toURL()},
                this.getClass().getClassLoader()
        )) {
            // 3. 加载目标类
            Class<?> targetClass = Class.forName(TARGET_CLASS, false, loader);
            System.out.println("✅ 成功加载目标类: " + targetClass.getName());

            // 4. 校验 getBuyPrice 方法
            // 假设 ASM 注入期望的签名是: double getBuyPrice(Player p, String itemID)
            // 你需要根据 EcoShopTransformer 里的实际描述符来写这里的参数类型
            Method buyMethod = assertDoesNotThrow(() -> targetClass.getDeclaredMethod(HOOK_METHOD_BUY, String.class),
                    "❌ 致命错误：UltimateShop 移除了 " + HOOK_METHOD_BUY + " 方法！ASM 注入将失败。");
            
            // 校验返回值类型
            assertEquals(double.class, buyMethod.getReturnType(), 
                    "❌ 致命错误：" + HOOK_METHOD_BUY + " 返回值类型已变更！");

            System.out.println("✅ " + HOOK_METHOD_BUY + " 签名校验通过: " + Type.getMethodDescriptor(buyMethod));

            // 5. 校验 getSellPrice 方法
            Method sellMethod = assertDoesNotThrow(() -> targetClass.getDeclaredMethod(HOOK_METHOD_SELL, String.class),
                    "❌ 致命错误：UltimateShop 移除了 " + HOOK_METHOD_SELL + " 方法！ASM 注入将失败。");
            
            assertEquals(double.class, sellMethod.getReturnType(), 
                    "❌ 致命错误：" + HOOK_METHOD_SELL + " 返回值类型已变更！");
            
            System.out.println("✅ " + HOOK_METHOD_SELL + " 签名校验通过: " + Type.getMethodDescriptor(sellMethod));
        }
    }
}
