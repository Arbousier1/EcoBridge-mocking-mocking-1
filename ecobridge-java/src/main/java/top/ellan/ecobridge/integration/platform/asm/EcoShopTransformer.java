package top.ellan.ecobridge.integration.platform.asm;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;
import top.ellan.ecobridge.util.LogUtil;

import java.lang.instrument.ClassFileTransformer;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * EcoShopTransformer (v2.1 - Schema Safe Edition)
 * <p>
 * 修复记录:
 * 1. [CRITICAL] 移除了 visitField 注入逻辑，解决了 "class redefinition failed" 崩溃。
 * 2. [Logic] LimitAdapter 改为后置修正，支持 Shadow Mode 观察原插件逻辑。
 * 3. [Safety] 使用 ASM 指令扫描替代字段标记进行幂等性检查。
 */
public class EcoShopTransformer implements ClassFileTransformer {

    private static final String TARGET_CLASS = "cn/superiormc/ultimateshop/objects/buttons/ObjectItem";
    // 桥接类的内部名，用于检测是否已注入
    private static final String BRIDGE_CLASS_INTERNAL = "top/ellan/ecobridge/integration/platform/asm/ASMBridge";
    
    private static final AtomicBoolean logged = new AtomicBoolean(false);

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        // 1. 基础类名过滤
        if (className == null || !className.equals(TARGET_CLASS)) {
            return null; // 不处理，显式返回 null
        }

        // 2. CodeSource 强校验 (防止 Shade/Fork 版本误伤)
        if (protectionDomain != null) {
            CodeSource src = protectionDomain.getCodeSource();
            if (src == null) return null; // 无法确认来源，跳过

            String location = src.getLocation().toString();
            // 确保只注入 UltimateShop 及其变种 (根据实际情况调整关键字)
            if (!location.contains("UltimateShop") && !location.contains("Superior")) {
                if (!logged.get()) LogUtil.warn("[ASM] 发现同名类但来源不符，跳过注入: " + location);
                return null;
            }
        }

        // 3. Fail-Safe 转换流程
        try {
            ClassReader cr = new ClassReader(classfileBuffer);

            // [修复] 使用指令扫描代替字段检查，防止 Schema Change 错误
            if (isAlreadyHooked(cr)) {
                // 已注入过，直接返回当前字节码 (幂等性保护)
                return classfileBuffer;
            }

            // COMPUTE_FRAMES: 自动计算栈帧，兼容 Java 21+
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES) {
                @Override
                protected String getCommonSuperClass(String type1, String type2) {
                    try {
                        return super.getCommonSuperClass(type1, type2);
                    } catch (Exception e) {
                        // 兜底策略：防止 ClassLoader 隔离导致的计算崩溃
                        LogUtil.warnOnce("asm-hierarchy", "[ASM] 层级计算失败，降级为 Object: " + e.getMessage());
                        return "java/lang/Object";
                    }
                }
            };

            // 注入状态追踪
            AtomicBoolean methodInjected = new AtomicBoolean(false);

            ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

                    // --- A. 价格逻辑 (后置修正) ---
                    // 目标: getBuyPrice(), getSellPrice() -> 返回 ObjectPrices
                    if ((name.equals("getBuyPrice") || name.equals("getSellPrice")) &&
                            descriptor.endsWith("Lcn/superiormc/ultimateshop/objects/items/prices/ObjectPrices;")) {
                        methodInjected.set(true);
                        return new PriceAdapter(Opcodes.ASM9, mv, access, name, descriptor);
                    }

                    // --- B. 限额逻辑 (后置修正) ---
                    // 目标: getPlayerBuyLimit(Player)... -> 返回 int
                    // 使用 loose matching 适应潜在的版本参数变化
                    if ((name.startsWith("getPlayer") || name.startsWith("getServer")) &&
                            name.endsWith("Limit") && descriptor.endsWith(")I")) {
                        methodInjected.set(true);
                        return new LimitAdapter(Opcodes.ASM9, mv, access, name, descriptor);
                    }

                    return mv;
                }
                
                // [重要] 移除了 visitEnd 中的 visitField，严禁修改类结构！
            };

            cr.accept(cv, ClassReader.EXPAND_FRAMES);

            // 4. 结果校验
            if (!methodInjected.get()) {
                LogUtil.warnOnce("asm-mismatch", "❌ EcoShopTransformer 类匹配但无方法被注入！可能是插件版本不兼容。");
                return classfileBuffer;
            }

            if (!logged.getAndSet(true)) {
                LogUtil.info("✔ EcoShop 字节码增强成功 (ASM Hook Active)");
            }
            return cw.toByteArray();

        } catch (Throwable t) {
            // 5. 最终防线：发生任何错误都返回原字节码，绝不让服务器崩
            LogUtil.severe("❌ EcoShopTransformer 注入失败! 已回滚至原生逻辑。", t);
            return classfileBuffer;
        }
    }

    /**
     * [修复] 通过扫描方法调用指令来检测是否已注入
     * 替代了之前的字段检测，避免了 Schema Change 异常
     */
    private boolean isAlreadyHooked(ClassReader cr) {
        try {
            AtomicBoolean hooked = new AtomicBoolean(false);
            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    // 只需扫描几个关键方法
                    if (hooked.get() || (!name.equals("getBuyPrice") && !name.endsWith("Limit"))) return null;
                    
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                            // 如果发现了对 EcoBridge ASMBridge 的调用，说明已经注入过了
                            if (owner.contains("top/ellan/ecobridge/integration/platform/asm/ASMBridge")) {
                                hooked.set(true);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return hooked.get();
        } catch (Exception e) {
            return false;
        }
    }

    // --- Adapters ---

    /**
     * 价格拦截适配器
     * 逻辑: 原方法返回 -> 栈顶是 ObjectPrices -> 调用 redirectPrice -> 返回修改后的 ObjectPrices
     */
    private static class PriceAdapter extends AdviceAdapter {
        private final String methodName;

        protected PriceAdapter(int api, MethodVisitor mv, int access, String name, String desc) {
            super(api, mv, access, name, desc);
            this.methodName = name;
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode == ARETURN) { // 拦截引用类型返回
                // Stack: [Original_ObjectPrices]
                loadThis(); // -> [Original, this]
                swap();     // -> [this, Original]
                push(methodName.equals("getBuyPrice")); // -> [this, Original, isBuy]
                
                // static redirectPrice(ObjectItem, ObjectPrices, boolean) -> ObjectPrices
                invokeStatic(Type.getType("L" + BRIDGE_CLASS_INTERNAL + ";"),
                        new Method("redirectPrice",
                                "(Lcn/superiormc/ultimateshop/objects/buttons/ObjectItem;Lcn/superiormc/ultimateshop/objects/items/prices/ObjectPrices;Z)Lcn/superiormc/ultimateshop/objects/items/prices/ObjectPrices;"));
            }
        }
    }

    /**
     * 限额拦截适配器
     * 逻辑: 原方法返回 -> 栈顶是 int (limit) -> 调用 adjustLimit -> 返回修改后的 int
     */
    private static class LimitAdapter extends AdviceAdapter {
        private final String methodName;

        protected LimitAdapter(int api, MethodVisitor mv, int access, String name, String desc) {
            super(api, mv, access, name, desc);
            this.methodName = name;
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode == IRETURN) { // 拦截 int 返回
                // Stack: [Original_Limit_Int]
                loadThis(); // -> [Original, this]
                swap();     // -> [this, Original]
                push(methodName.contains("Buy")); // -> [this, Original, isBuy]
                
                // static adjustLimit(ObjectItem, int, boolean) -> int
                invokeStatic(Type.getType("L" + BRIDGE_CLASS_INTERNAL + ";"),
                        new Method("adjustLimit",
                                "(Lcn/superiormc/ultimateshop/objects/buttons/ObjectItem;IZ)I"));
            }
        }
    }
}