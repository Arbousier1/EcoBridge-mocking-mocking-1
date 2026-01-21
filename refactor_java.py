import os
import re
import shutil
from pathlib import Path

# ================= 配置区域 =================
SRC_ROOT = Path("ecobridge-java/src/main/java/top/ellan/ecobridge")
BASE_PACKAGE = "top.ellan.ecobridge"

# 映射表：{ "当前路径": "DDD 目标路径" }
# 路径相对于 top/ellan/ecobridge
DDD_MAPPING = {
    # 1. Domain 领域层 (心脏)
    "core/engine":      "domain/algorithm",
    "model":            "domain/model",
    "transaction":      "domain/transaction",
    
    # 2. Application 应用层 (指挥官)
    "core/manager":     "application/service",
    
    # 3. Infrastructure 基础设施层 (工具/持久化)
    "core/cache":       "infrastructure/cache",
    "data/database":    "infrastructure/persistence/database",
    "data/redis":       "infrastructure/persistence/redis",
    "data/storage":     "infrastructure/persistence/storage",
    "data/transaction": "infrastructure/persistence/transaction",
    "ffi/bridge":       "infrastructure/ffi/bridge",    # 规避 native 关键字
    "ffi/model":        "infrastructure/ffi/model",
    
    # 4. Integration / Platform 接入层 (触手)
    "platform/asm":      "integration/platform/asm",
    "platform/command":  "integration/platform/command",
    "platform/hook":     "integration/platform/hook",
    "platform/listener": "integration/platform/listener",
}

# ===========================================

def ddd_migrate():
    if not SRC_ROOT.exists():
        print(f"❌ 错误: 找不到路径 {SRC_ROOT}")
        return

    # 1. 预计算所有包名替换对 (点号和斜杠)
    pkg_replacements = []
    for old_path, new_path in DDD_MAPPING.items():
        old_dot = f"{BASE_PACKAGE}.{old_path.replace('/', '.')}"
        new_dot = f"{BASE_PACKAGE}.{new_path.replace('/', '.')}"
        old_slash = f"{BASE_PACKAGE.replace('.', '/')}/{old_path}"
        new_slash = f"{BASE_PACKAGE.replace('.', '/')}/{new_path}"
        
        pkg_replacements.append((old_dot, new_dot))
        pkg_replacements.append((old_slash, new_slash))

    # 按长度降序排列，防止短路径误伤长路径
    pkg_replacements.sort(key=lambda x: len(x[0]), reverse=True)

    print("🏗️  正在构建 DDD 物理结构...")
    # 2. 执行物理移动
    for old_path_str, new_path_str in DDD_MAPPING.items():
        old_dir = SRC_ROOT / old_path_str
        new_dir = SRC_ROOT / new_path_str
        
        if old_dir.exists() and old_dir.is_dir():
            new_dir.mkdir(parents=True, exist_ok=True)
            for item in list(old_dir.iterdir()):
                if item.is_file():
                    shutil.move(str(item.absolute()), str((new_dir / item.name).absolute()))
            
            # 递归删除空旧目录
            try:
                os.removedirs(old_dir)
            except OSError:
                pass 
            print(f"📦 已迁移模块: {old_path_str} -> {new_path_str}")

    print("\n💉 正在进行全量引用注入 (Package/Import/ASM Strings)...")
    # 3. 扫描所有 Java 文件进行内容替换
    # 范围扩大到 src/main/java 确保主类 EcoBridge 也被覆盖
    for java_file in SRC_ROOT.parent.rglob("*.java"):
        if not java_file.is_file(): continue
        
        try:
            with open(java_file, 'r', encoding='utf-8') as f:
                content = f.read()

            new_content = content
            for old, new in pkg_replacements:
                new_content = new_content.replace(old, new)

            if new_content != content:
                with open(java_file, 'w', encoding='utf-8') as f:
                    f.write(new_content)
                print(f"📝 修正引用: {java_file.name}")
        except Exception as e:
            print(f"⚠️ 处理文件失败 {java_file.name}: {e}")

    # 4. 尝试修正 paper-plugin.yml
    yml_path = Path("ecobridge-java/src/main/resources/paper-plugin.yml")
    if yml_path.exists():
        with open(yml_path, 'r', encoding='utf-8') as f:
            yml_content = f.read()
        # 简单替换 main 类路径（如果 main 类没动就不变）
        # 实际上根据蓝图，EcoBridge.java 在根包没动，所以可能不需要
        print("ℹ️  请手动确认 paper-plugin.yml 中的 main 路径是否正确。")

    print("\n✨ DDD 重构任务完成！")
    print("👉 建议执行: ./gradlew clean")
    print("👉 VSCode 提示: 使用 'Clean Java Language Server Workspace' 刷新索引。")

if __name__ == "__main__":
    ddd_migrate()