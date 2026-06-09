#!/usr/bin/env python3
"""
PL/SQL → JSON AST 转换器。

使用 ANTLR4 Java TestRig 解析 PL/SQL 代码，输出完整的 JSON 语法树。

用法:
  python3 plsql_to_ast.py input.sql > output.json
  python3 plsql_to_ast.py -i input.sql -o output.json
  cat input.sql | python3 plsql_to_ast.py -
"""

import json
import os
import re
import subprocess
import sys

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
ANTLR_JAR = os.path.join(PROJECT_DIR, "plsql-translator-java", "lib", "antlr-4.9.3-complete.jar")
CLASSES_DIR = os.path.join(PROJECT_DIR, "plsql-translator-java", "target", "classes")
PARSER_CLASS = "com.plsql.translator.parser.PlSql"


def parse_plsql(source_text):
    """调用 ANTLR 的 TestRig 获取解析树文本表示。"""
    import tempfile
    with tempfile.NamedTemporaryFile(mode='w', suffix='.sql', delete=False) as f:
        f.write(source_text)
        tmpfile = f.name

    try:
        result = subprocess.run(
            ["java", "-cp", f"{ANTLR_JAR}:{CLASSES_DIR}",
             "org.antlr.v4.gui.TestRig",
             PARSER_CLASS, "sql_script", "-tree", tmpfile],
            capture_output=True, text=True, timeout=30
        )
        if result.returncode != 0:
            raise RuntimeError(f"ANTLR parse failed: {result.stderr}")
        return result.stdout.strip()
    finally:
        os.unlink(tmpfile)


def parse_tree_string(tree_str):
    """将 ANTLR 的 LISP 风格树字符串解析为嵌套 Python 结构。

    格式: (rule_name child1 child2 ...)
    叶子节点是原始单词或符号。
    """
    tree_str = tree_str.strip()
    pos = [0]  # mutable position tracker

    def skip_ws():
        while pos[0] < len(tree_str) and tree_str[pos[0]] in ' \t\n\r':
            pos[0] += 1

    def parse_node():
        skip_ws()
        if pos[0] >= len(tree_str):
            return None

        if tree_str[pos[0]] == '(':
            pos[0] += 1  # skip '('
            skip_ws()

            # 读取规则名（直到空格或 )）
            name_start = pos[0]
            while pos[0] < len(tree_str) and tree_str[pos[0]] not in ' \t\n\r)':
                pos[0] += 1
            rule_name = tree_str[name_start:pos[0]]

            children = []
            while pos[0] < len(tree_str):
                skip_ws()
                if pos[0] >= len(tree_str):
                    break
                if tree_str[pos[0]] == ')':
                    pos[0] += 1  # skip ')'
                    break
                child = parse_node()
                if child is not None:
                    children.append(child)

            return {"rule": rule_name, "children": children} if children else {"rule": rule_name}

        else:
            # 叶子节点：读取到空格或 )
            start = pos[0]
            while pos[0] < len(tree_str) and tree_str[pos[0]] not in ' \t\n\r)':
                pos[0] += 1
            text = tree_str[start:pos[0]]
            if text:
                return {"token": text}
            return None

    return parse_node()


def simplify_ast(node, max_depth=20):
    """简化 AST：移除只有叶子的规则节点，合并单子节点。"""
    if node is None:
        return None

    if "token" in node:
        return node

    if "rule" in node:
        children = node.get("children", [])
        if not children:
            return node

        # 递归简化子节点
        simplified = [simplify_ast(c, max_depth - 1) for c in children if c is not None]
        simplified = [c for c in simplified if c is not None]

        # 跳过纯空白 token
        filtered = []
        for c in simplified:
            if c.get("token") and not c["token"].strip():
                continue
            filtered.append(c)
        simplified = filtered

        if not simplified:
            return node

        # 如果只有一个子节点且是规则，合并
        if len(simplified) == 1 and "rule" in simplified[0]:
            child = simplified[0]
            child["rule"] = node["rule"] + ">" + child["rule"]
            return child

        node["children"] = simplified
        return node

    return node


def ast_to_json(ast, indent=2):
    """将 AST 转为格式化的 JSON 字符串。"""
    return json.dumps(ast, ensure_ascii=False, indent=indent)


def main():
    if len(sys.argv) < 2 or sys.argv[1] in ('-h', '--help'):
        print(__doc__)
        sys.exit(0)

    # 读取输入
    source = None
    if sys.argv[1] == '-':
        source = sys.stdin.read()
    elif sys.argv[1] == '-i' and len(sys.argv) >= 3:
        with open(sys.argv[2]) as f:
            source = f.read()
    else:
        with open(sys.argv[1]) as f:
            source = f.read()

    # 解析
    tree_str = parse_plsql(source)
    ast = parse_tree_string(tree_str)
    ast = simplify_ast(ast)

    json_output = ast_to_json(ast)

    # 输出
    if '-o' in sys.argv:
        idx = sys.argv.index('-o')
        with open(sys.argv[idx + 1], 'w') as f:
            f.write(json_output)
        print(f"✓ AST saved: {sys.argv[idx + 1]}", file=sys.stderr)
    else:
        print(json_output)


if __name__ == '__main__':
    main()
