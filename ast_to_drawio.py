#!/usr/bin/env python3
"""
AST JSON → draw.io 图转换器（纯 XML 生成，零依赖）。

直接把 AST 生成为 drawio 格式的 XML。不需要任何第三方库。
drawio 格式本身就是 XML + mxGraph 规范。

用法:
  python3 ast_to_drawio.py [ast.json] [output.drawio]
  python3 ast_to_drawio.py -i ast.json -o tree.drawio
  echo '{"rule":"..."}' | python3 ast_to_drawio.py -o tree.drawio
"""

import json
import os
import sys
import xml.etree.ElementTree as ET
from xml.dom import minidom


# ── 布局参数 ─────────────────────────────────────────────
NODE_W  = 160     # 节点宽度
NODE_H  = 34      # 节点高度
H_GAP   = 6       # 同级水平间距
V_GAP   = 40      # 层级垂直间距
PAD     = 30      # 画布边距
FONT_SZ = 10      # 字号

# ── 主题色 ───────────────────────────────────────────────
COLORS = {
    "root":     "#1e1e2e",
    "rule":     "#2d2d44",
    "keyword":  "#c92a2a",
    "token":    "#364fc7",
    "string":   "#2b8a3e",
    "number":   "#e67700",
    "comment":  "#495057",
    "edge":     "#5c5c7a",
    "bg":       "#f8f9fa",
}


def load_ast(path_or_none):
    if path_or_none is None or path_or_none == "-":
        return json.load(sys.stdin)
    with open(path_or_none) as f:
        return json.load(f)


def assign_ids(node, counter=None):
    if counter is None:
        counter = [1]
    node["_id"] = f"n{counter[0]}"
    counter[0] += 1
    for c in node.get("children", []):
        assign_ids(c, counter)


def count_leaves(node):
    """计算子树叶子数（用于布局宽度）。"""
    if not node.get("children"):
        return 1
    return sum(count_leaves(c) for c in node["children"])


def layout(node, depth=0, x_start=0, store=None):
    """递归计算树布局。返回 (总宽度, store)。

    store: { id: (center_x, y, width, node) }
    """
    if store is None:
        store = {}

    children = node.get("children", [])

    if not children:
        w = NODE_W
        store[node["_id"]] = (x_start + w / 2, depth * (NODE_H + V_GAP), w, node)
        return w, store

    # 遍历子节点
    child_x = x_start
    child_ws = []
    for child in children:
        cw, store = layout(child, depth + 1, child_x, store)
        child_ws.append(cw)
        child_x += cw

    total_w = max(NODE_W, sum(child_ws))
    cx = x_start + total_w / 2
    store[node["_id"]] = (cx, depth * (NODE_H + V_GAP), total_w, node)
    return total_w, store


def node_style(node, depth):
    """生成节点 CSS 样式字符串。"""
    is_rule = "rule" in node
    is_token = "token" in node

    # 默认样式
    style = {
        "rounded": "1" if is_rule else "0",
        "whiteSpace": "wrap",
        "html": "1",
        "fontSize": str(FONT_SZ),
        "fontColor": "#ffffff",
        "strokeColor": "none",
        "overflow": "hidden",
        "labelBackgroundColor": "none",
    }

    # 选色
    if is_rule:
        if depth == 0:
            style["fillColor"] = COLORS["root"]
            style["fontSize"] = str(FONT_SZ + 1)
        elif any(
            kw in node["rule"]
            for kw in ["procedure", "function", "package", "trigger", "type", "body"]
        ):
            style["fillColor"] = COLORS["string"]
        else:
            style["fillColor"] = COLORS["rule"]

    if is_token:
        t = node["token"]
        if t.isupper() and len(t) > 1 and "_" not in t:
            style["fillColor"] = COLORS["keyword"]
            style["fontStyle"] = "4"  # bold
        elif t.startswith('"') or t.startswith("'"):
            style["fillColor"] = COLORS["string"]
        elif t.isdigit():
            style["fillColor"] = COLORS["number"]
        else:
            style["fillColor"] = COLORS["token"]
            style["fontStyle"] = "3"  # italic

    return ";".join(f"{k}={v}" for k, v in style.items())


def shorten(s, max_len=28):
    if len(s) > max_len:
        return s[: max_len - 1] + "…"
    return s


def make_editable_safe(s):
    """转义 XML 特殊字符。"""
    return (
        s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
        .replace("'", "&apos;")
    )


def layout_label(node):
    """生节点显示标签。"""
    if "rule" in node:
        name = node["rule"].split(">")[-1]
        name = name.replace("_", " ")
        return shorten(name)
    if "token" in node:
        t = node["token"]
        if len(t) > 30:
            t = t[:28] + "…"
        return make_editable_safe(t)
    return "?"


def generate_drawio_xml(ast, output_path, max_node_width=None):
    """直接生成 drawio XML，零依赖。"""
    assign_ids(ast)
    _, positions = layout(ast, 0, PAD)

    if not positions:
        print("错误: 无法计算布局")
        return

    # 画布大小
    max_x = max(cx + w / 2 for cx, _, w, _ in positions.values()) + PAD
    max_y = max(y + NODE_H for _, y, _, _ in positions.values()) + PAD

    # 创建 XML 文档
    impl = minidom.getDOMImplementation()
    doc = impl.createDocument(None, "mxfile", None)
    mxfile = doc.documentElement
    mxfile.setAttribute("host", "AST Auto-Generator")
    mxfile.setAttribute("modified", "2026")
    mxfile.setAttribute("agent", "plsql-ast")
    mxfile.setAttribute("type", "device")

    diagram = doc.createElement("diagram")
    diagram.setAttribute("name", "PL/SQL AST")
    diagram.setAttribute("id", "ast-1")
    mxfile.appendChild(diagram)

    model = doc.createElement("mxGraphModel")
    model.setAttribute("dx", str(int(max_x * 0.1)))
    model.setAttribute("dy", str(int(max_y * 0.1)))
    model.setAttribute("grid", "1")
    model.setAttribute("gridSize", "10")
    model.setAttribute("pageWidth", str(int(max_x)))
    model.setAttribute("pageHeight", str(int(max_y)))
    model.setAttribute("background", COLORS["bg"])
    diagram.appendChild(model)

    root = doc.createElement("root")
    model.appendChild(root)

    # 默认单元格
    cell0 = doc.createElement("mxCell")
    cell0.setAttribute("id", "0")
    root.appendChild(cell0)

    cell1 = doc.createElement("mxCell")
    cell1.setAttribute("id", "1")
    cell1.setAttribute("parent", "0")
    root.appendChild(cell1)

    # 顶点映射
    draw_cells = {}

    cell_id_counter = [2]

    def next_id():
        i = cell_id_counter[0]
        cell_id_counter[0] += 1
        return str(i)

    # 创建顶点
    for nid, (cx, y, w, node) in positions.items():
        label = layout_label(node)
        style_str = node_style(node, int(y / (NODE_H + V_GAP)))

        cell = doc.createElement("mxCell")
        cell.setAttribute("id", next_id())
        cell.setAttribute("value", label)
        cell.setAttribute("vertex", "1")
        cell.setAttribute("parent", "1")
        cell.setAttribute("style", style_str)

        geom = doc.createElement("mxGeometry")
        geom.setAttribute("x", str(cx - w / 2))
        geom.setAttribute("y", str(y))
        geom.setAttribute("width", str(w))
        geom.setAttribute("height", str(NODE_H))
        geom.setAttribute("as", "geometry")
        cell.appendChild(geom)

        root.appendChild(cell)
        draw_cells[nid] = cell

    # 创建边
    def add_edges(node):
        children = node.get("children", [])
        if children:
            parent_cell = draw_cells.get(node["_id"])
            if parent_cell:
                for child in children:
                    child_cell = draw_cells.get(child["_id"])
                    if child_cell:
                        edge = doc.createElement("mxCell")
                        edge.setAttribute("id", next_id())
                        edge.setAttribute("edge", "1")
                        edge.setAttribute("parent", "1")
                        edge.setAttribute("source", parent_cell.getAttribute("id"))
                        edge.setAttribute("target", child_cell.getAttribute("id"))
                        edge.setAttribute(
                            "style",
                            "edgeStyle=orthogonalEdgeStyle;"
                            f"strokeColor={COLORS['edge']};"
                            "strokeWidth=0.5;"
                            "rounded=1;"
                        )

                        edge_geom = doc.createElement("mxGeometry")
                        edge_geom.setAttribute("relative", "1")
                        edge_geom.setAttribute("as", "geometry")
                        edge.appendChild(edge_geom)

                        root.appendChild(edge)
                    add_edges(child)

    add_edges(ast)

    # 写文件
    xml_str = doc.toprettyxml(indent="  ", encoding="utf-8").decode("utf-8")

    with open(output_path, "w", encoding="utf-8") as f:
        f.write(xml_str)

    n_nodes = len(draw_cells)
    n_edges = sum(
        1
        for _ in root.getElementsByTagName("mxCell")
        if _.getAttribute("edge") == "1"
    )

    print(f"✓ 已生成: {output_path}")
    print(f"  节点: {n_nodes}  边: {n_edges}  文件: {os.path.getsize(output_path) // 1024} KB")
    print(f"  画布: {max_x:.0f} × {max_y:.0f}")


def main():
    import argparse

    parser = argparse.ArgumentParser(description="PL/SQL AST → drawio 图")
    parser.add_argument("input", nargs="?", help="JSON AST 文件 (- 表示 stdin)")
    parser.add_argument("-o", "--output", default=None, help="输出 .drawio 文件路径")
    args = parser.parse_args()

    ast_path = args.input
    ast = load_ast(ast_path)

    # 默认输出路径
    output = args.output
    if not output:
        if ast_path and ast_path != "-":
            output = ast_path.replace(".json", ".drawio")
        else:
            output = "ast_tree.drawio"

    generate_drawio_xml(ast, output)


if __name__ == "__main__":
    main()
