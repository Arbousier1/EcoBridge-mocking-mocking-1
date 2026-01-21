import os
from pathlib import Path

class ProjectTreeGenerator:
    def __init__(self, root_dir=".", output_file="project_tree.txt", max_depth=15):
        self.root_path = Path(root_dir).resolve()
        self.output_file = output_file
        # 调高了最大深度，Java 的包结构通常需要 8-10 层才能看到代码
        self.max_depth = max_depth
        
        # 1. 核心忽略名单：剔除那些动辄成千上万文件的缓存目录
        self.ignore_dirs = {
            '.git', '__pycache__', '.venv', 'venv', '.vscode', '.idea', 
            '.gradle', 'target', 'build', 'bin', 'out'
        }
        
        # 2. 忽略特定后缀：排除 Python 脚本、编译字节码、Rust 指纹等
        self.ignore_exts = {
            '.py', '.pyc', '.class', '.jar', '.d', '.timestamp', '.json'
        }
        
        self.tree_str = ""

    def _should_ignore(self, item):
        """判断是否应该过滤掉"""
        # 忽略自身
        if item.name == self.output_file:
            return True
        # 忽略缓存目录
        if item.is_dir() and item.name in self.ignore_dirs:
            return True
        # 忽略 .py 文件及编译产物
        if item.is_file() and (item.suffix.lower() in self.ignore_exts or item.name.endswith('.py')):
            return True
        return False

    def _build_tree(self, current_path, prefix="", depth=0):
        if depth > self.max_depth:
            return

        try:
            # 过滤并排序
            items = [item for item in current_path.iterdir() if not self._should_ignore(item)]
            items.sort(key=lambda x: (x.is_file(), x.name.lower()))
        except PermissionError:
            return

        for i, item in enumerate(items):
            is_last = (i == len(items) - 1)
            connector = "└── " if is_last else "├── "
            
            # 拼接显示名称
            display_name = f"{item.name}/" if item.is_dir() else item.name
            self.tree_str += f"{prefix}{connector}{display_name}\n"
            
            if item.is_dir():
                # 递归：如果是最后一个元素，下方留白；否则画竖线
                new_prefix = prefix + ("    " if is_last else "│   ")
                self._build_tree(item, new_prefix, depth + 1)

    def generate_and_save(self):
        """执行生成并保存到 txt"""
        self.tree_str = f"📦 {self.root_path.name}/\n"
        self._build_tree(self.root_path)
        
        output_path = self.root_path / self.output_file
        
        with open(output_path, "w", encoding="utf-8") as f:
            f.write(self.tree_str)
        
        print(f"✅ 完整文件树已保存至: {output_path}")
        print(f"⚠️ 注意：已自动忽略了 .py 文件、.gradle、target 等构建目录。")

if __name__ == "__main__":
    # 如果你的项目比 15 层还深，可以手动修改 max_depth
    generator = ProjectTreeGenerator(max_depth=20) 
    generator.generate_and_save()