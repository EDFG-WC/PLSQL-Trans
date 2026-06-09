#!/usr/bin/env python3
"""
drawio 图正确性验证套件。

测试从 PL/SQL 源码生成的 drawio 图是否:
  1. XML 结构完整性
  2. 节点-边一致性（没有悬空边）
  3. 标签正确性（关键字是否出现）
  4. 尺寸合理性
  5. 与大文件对比验证
"""

import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

PROJECT = os.path.dirname(os.path.abspath(__file__))
ANTLR = os.path.join(PROJECT, "plsql-translator-java/lib/antlr-4.9.3-complete.jar")
JAR = os.path.join(PROJECT, "plsql-translator-java/target/plsql-translator.jar")
OUT = "/tmp/ast_test"
MAX_NODE_WIDTH = 250  # 节点宽度不应超过此值


def run_java(mode, src_path, out_path):
    """调用 Java 翻译器。"""
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    r = subprocess.run(
        ["java", "-cp", f"{ANTLR}:{JAR}",
         "com.plsql.translator.Main", "--mode", mode,
         src_path, out_path],
        capture_output=True, text=True, timeout=120)
    return r.returncode == 0, r.stdout.strip(), r.stderr[:200]


# =============================================================
# 测试 1: XML 格式验证
# =============================================================
def test_xml_validity(drawio_path):
    """drawio 文件必须是合法 XML，且包含 mxGraphModel。"""
    errors = []
    try:
        tree = ET.parse(drawio_path)
        root = tree.getroot()
    except ET.ParseError as e:
        return False, [f"XML 解析失败: {e}"]

    if root.tag != "mxfile":
        errors.append(f"根标签应为 mxfile, 实际为 {root.tag}")

    cells = list(root.iter("mxCell"))
    if not cells:
        errors.append("没有 mxCell 元素")

    # 必须有 parent cell (id=0 和 id=1)
    ids = [c.get("id") for c in cells]
    if "0" not in ids:
        errors.append("缺少根 mxCell(id=0)")
    if "1" not in ids:
        errors.append("缺少根 mxCell(id=1)")

    return len(errors) == 0, errors


# =============================================================
# 测试 2: 边-节点一致性
# =============================================================
def test_edge_consistency(drawio_path):
    """所有边的 source/target 必须指向存在的 vertex。"""
    tree = ET.parse(drawio_path)
    root = tree.getroot()

    vertices = {}
    edges = []
    for cell in root.iter("mxCell"):
        cid = cell.get("id")
        if cell.get("vertex") == "1":
            vertices[cid] = cell.get("value", "")
        if cell.get("edge") == "1":
            edges.append((cid, cell.get("source"), cell.get("target")))

    errors = []
    for eid, src, tgt in edges:
        if src not in vertices:
            errors.append(f"边 {eid}: source={src} 不存在")
        if tgt not in vertices:
            errors.append(f"边 {eid}: target={tgt} 不存在")

    return len(errors) == 0, errors, len(vertices), len(edges)


# =============================================================
# 测试 3: 标签内容验证
# =============================================================
def test_keywords_present(drawio_path, expected_keywords):
    """关键 PL/SQL 关键字必须在节点标签中出现。"""
    tree = ET.parse(drawio_path)
    root = tree.getroot()

    labels = set()
    for cell in root.iter("mxCell"):
        if cell.get("vertex") == "1":
            val = cell.get("value", "")
            if val:
                labels.add(val.strip())

    missing = [kw for kw in expected_keywords
               if not any(kw.lower() in l.lower() for l in labels)]

    return len(missing) == 0, missing, labels


# =============================================================
# 测试 4: 布局合理性
# =============================================================
def test_layout_sanity(drawio_path):
    """节点位置不应该重叠，画布大小应合理。"""
    tree = ET.parse(drawio_path)
    root = tree.getroot()

    positions = []
    for cell in root.iter("mxCell"):
        if cell.get("vertex") != "1":
            continue
        geom = cell.find("mxGeometry")
        if geom is None:
            continue
        x = float(geom.get("x", 0))
        y = float(geom.get("y", 0))
        w = float(geom.get("width", 0))
        h = float(geom.get("height", 0))
        positions.append((x, y, w, h))

    errors = []
    # 检查是否有大量重叠
    for i, (x1, y1, w1, h1) in enumerate(positions):
        for j, (x2, y2, w2, h2) in enumerate(positions):
            if i >= j:
                continue
            if x1 < x2 + w2 and x1 + w1 > x2 and y1 < y2 + h2 and y1 + h1 > y2:
                errors.append(f"节点重叠: ({x1},{y1}) 与 ({x2},{y2})")

    # 检查节点宽度是否合理
    max_w = max((w for _, _, w, _ in positions), default=0)
    if max_w > 0 and max_w > MAX_NODE_WIDTH:
        errors.append(f"节点过宽: {max_w:.0f}px (最大允许 {MAX_NODE_WIDTH}px)")

    return len(errors) == 0, errors, len(positions)


# =============================================================
# 测试 5: 文件尺寸与源文件关系
# =============================================================
def test_size_correlation(src_path, drawio_path):
    """drawio 大小应在源文件大小的合理比例内。"""
    src_sz = os.path.getsize(src_path)
    dw_sz = os.path.getsize(drawio_path)

    # drawio 不应小于源文件的 0.1% 也不应大于 500 倍
    min_ratio = 0.001
    max_ratio = 500
    ratio = dw_sz / src_sz if src_sz > 0 else 0

    errors = []
    if ratio < min_ratio:
        errors.append(f"drawio 太小 (ratio={ratio:.4f} < {min_ratio})")
    if ratio > max_ratio:
        errors.append(f"drawio 太大 (ratio={ratio:.1f} > {max_ratio})")

    return min_ratio <= ratio <= max_ratio, errors, src_sz, dw_sz, ratio


# =============================================================
# 运行全部测试
# =============================================================
def run_all_tests(src_path, expected_keywords=None, label=""):
    print(f"\n{'='*60}")
    print(f"  {label}")
    print(f"  源文件: {src_path}")
    print(f"{'='*60}")

    dw_path = os.path.join(OUT, f"test{label.replace(' ','_')}.drawio")

    # 生成 drawio
    ok, out_msg, err_msg = run_java("drawio", src_path, dw_path)
    if not ok:
        print(f"  ❌ 生成失败: {err_msg}")
        return

    tests = [
        ("XML 格式验证", test_xml_validity, dw_path),
        (
            "边-节点一致性",
            lambda p: (
                lambda ok, err, v, e: (
                    ok,
                    err,
                    {"vertices": v, "edges": e},
                )
            )(*test_edge_consistency(p)),
            dw_path,
        ),
        ("标签关键字验证",
         lambda p: test_keywords_present(p, expected_keywords or []),
         dw_path),
        ("布局合理性", test_layout_sanity, dw_path),
    ]

    passed = 0
    failed = 0
    details = {}

    for name, test_fn, path in tests:
        result = test_fn(path)
        if isinstance(result, tuple):
            ok = result[0]
            errs = result[1] if len(result) > 1 else []
            data = result[2] if len(result) > 2 else {}
        else:
            ok = result
            errs = []
            data = {}

        if ok:
            passed += 1
            print(f"  ✅ {name}")
        else:
            failed += 1
            print(f"  ❌ {name}")
            for e in (errs if isinstance(errs, list) else [errs]):
                print(f"      {str(e)[:80]}")

        details[name] = {"ok": ok, "data": data}

    # 尺寸对比
    ok, errs, src_sz, dw_sz, ratio = test_size_correlation(src_path, dw_path)
    if ok:
        passed += 1
        print(f"  ✅ 尺寸比例合理 ({src_sz//1024}KB → {dw_sz//1024}KB, ratio={ratio:.2f})")
    else:
        failed += 1
        print(f"  ❌ 尺寸异常")
        for e in errs:
            print(f"      {e}")

    total = passed + failed
    print(f"\n  结果: {passed}/{total} 通过, {failed} 失败")

    return details


# =============================================================
# 主入口
# =============================================================
if __name__ == "__main__":
    # 测试 1: 简单存储过程
    run_all_tests(
        os.path.join(PROJECT, "plsql-translator-java/examples/test_procedure.sql"),
        expected_keywords=["procedure", "update_employee_salary",
                           "NUMBER", "SELECT", "UPDATE"],
        label="简单存储过程 test_procedure.sql",
    )

    # 测试 2: 中等大小的包规范
    run_all_tests(
        os.path.join(PROJECT, "data/PACKAGE/jabautista_GeniisysSCA/"
                     "jabautista_GeniisysSCA__src__main__resources__sql__"
                     "packages__giuw_pol_dist_final_pkg.pks"),
        expected_keywords=["package", "giuw_pol_dist_final_pkg",
                           "procedure", "function"],
        label="中等包规范 giuw_pol_dist_final_pkg.pks",
    )
