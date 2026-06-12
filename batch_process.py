#!/usr/bin/env python3
"""
批量处理：遍历爬取到的 PL/SQL 文件，生成 Java 源码和 Graphviz DOT 图。

用法:
  python3 batch_process.py                              全部处理
  python3 batch_process.py --categories PROCEDURE       只处理 PROCEDURE
  python3 batch_process.py --max 100                    只处理前 100 个
  python3 batch_process.py --mode java                  只生成 Java
  python3 batch_process.py --mode graphviz              只生成 ANTLR 语法树 DOT 图
  python3 batch_process.py --mode flow-graphviz         只生成控制流 DOT 图
  python3 batch_process.py --mode both                  生成所有（默认）
"""

import argparse
import os
import subprocess
import sys
import time
from pathlib import Path

PROJECT_DIR = Path(__file__).parent.resolve()
DATA_DIR = PROJECT_DIR / "data"
JAVA_DIR = PROJECT_DIR / "output" / "java"
GRAPHVIZ_DIR = PROJECT_DIR / "output" / "graphviz"
FLOW_DIR = PROJECT_DIR / "output" / "flow-graphviz"
JAR = PROJECT_DIR / "plsql-translator-java" / "target" / "plsql-translator.jar"
ANTLR = PROJECT_DIR / "plsql-translator-java" / "lib" / "antlr-4.9.3-complete.jar"

# 跳过这些（二进制/过大/过小）
SKIP_EXT = {".png", ".jpg", ".gif", ".zip", ".jar", ".class", ".pyc", ".pyo"}
MIN_SIZE = 20      # 最小 20 字节
MAX_SIZE = 5 * 1024 * 1024  # 最大 5 MB


def find_plsql_files(categories=None, max_files=None):
    """扫描 data/ 目录，找到所有 PL/SQL 文件。"""
    files = []
    for root, dirs, filenames in os.walk(DATA_DIR):
        # 跳过 log/sh 文件
        dirs[:] = [d for d in dirs if not d.startswith('.')
                   and d not in ('__pycache__')]

        rel = Path(root).relative_to(DATA_DIR)
        category = str(rel.parts[0]) if rel.parts else ""

        if categories and category not in categories:
            continue

        for fn in filenames:
            ext = Path(fn).suffix.lower()
            if ext in SKIP_EXT:
                continue
            fp = Path(root) / fn
            sz = fp.stat().st_size
            if sz < MIN_SIZE or sz > MAX_SIZE:
                continue
            # 跳过非文本文件（检查前几个字节）
            try:
                with open(fp, 'rb') as f:
                    head = f.read(256)
                head.decode('utf-8')  # 确保是文本
            except (UnicodeDecodeError, OSError):
                continue
            files.append((fp, category))

    # 按分类排序
    files.sort(key=lambda x: (x[1], str(x[0])))
    if max_files:
        files = files[:max_files]
    return files


def process_file(src_path, category, mode):
    """处理单个文件：生成 java 或 graphviz。"""
    rel = src_path.relative_to(DATA_DIR)

    if mode == "java":
        out_dir = JAVA_DIR
        out_ext = ".plsql.java"
    elif mode == "graphviz":
        out_dir = GRAPHVIZ_DIR
        out_ext = ".dot"
    else:
        out_dir = FLOW_DIR
        out_ext = ".flow.dot"

    out_path = out_dir / rel.with_suffix(out_ext)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    java_mode = mode
    if mode == "flow-graphviz":
        java_mode = "flow-graphviz"

    translate_sh = PROJECT_DIR / "plsql-translator-java" / "translate.sh"
    cmd = [str(translate_sh), "--mode", java_mode, str(src_path), str(out_path)]

    try:
        result = subprocess.run(
            cmd, capture_output=True, text=True, timeout=120
        )
        if result.returncode == 0:
            return True, out_path
        else:
            err = result.stderr.strip()[:200] or result.stdout.strip()[:200]
            return False, err
    except subprocess.TimeoutExpired:
        return False, "timeout"
    except Exception as e:
        return False, str(e)


def main():
    parser = argparse.ArgumentParser(description="PL/SQL 批量翻译 + 画图")
    parser.add_argument("--categories", nargs="+", default=None,
                        help="只处理指定分类，如 PROCEDURE FUNCTION PACKAGE")
    parser.add_argument("--max", type=int, default=None,
                        help="最多处理 N 个文件")
    parser.add_argument("--mode", default="both",
                        choices=["java", "graphviz", "flow-graphviz", "both"],
                        help="输出类型")
    parser.add_argument("--start-from", type=int, default=0,
                        help="从第 N 个文件开始（用于断点续传）")
    args = parser.parse_args()

    files = find_plsql_files(args.categories, args.max)
    total = len(files)
    if total == 0:
        print("没有找到 PL/SQL 文件")
        return

    modes_all = ["java", "graphviz", "flow-graphviz"]
    modes = modes_all if args.mode == "both" else [args.mode]

    print(f"找到 {total} 个 PL/SQL 文件，开始处理...")
    print(f"输出模式: {', '.join(modes)}")
    print()

    stats = {m: {"ok": 0, "fail": 0} for m in modes}
    start_time = time.time()

    for idx, (src_path, category) in enumerate(files):
        if idx < args.start_from:
            continue

        rel = src_path.relative_to(DATA_DIR)
        elapsed = time.time() - start_time
        pct = (idx + 1) / total * 100
        rate = (idx + 1 - args.start_from) / (elapsed + 0.001)
        eta_remaining = (total - idx - 1) / (rate + 0.001)

        print(f"[{idx+1}/{total}] {pct:.0f}%  {rel}  "
              f"({eta_remaining/60:.0f}m remaining)", end="")

        for mode in modes:
            ok, result = process_file(src_path, category, mode)
            if ok:
                stats[mode]["ok"] += 1
            else:
                stats[mode]["fail"] += 1
                print(f"  ⚠ {mode}: {result}", end="")

        print()

        # 每 50 个文件输出一次统计
        if (idx + 1) % 50 == 0:
            _show_stats(stats, elapsed, idx + 1)

    # 最终统计
    total_elapsed = time.time() - start_time
    print("\n" + "=" * 60)
    print(f"  完成! 耗时 {total_elapsed/60:.1f}m")
    for mode in modes:
        s = stats[mode]
        print(f"  {mode}: {s['ok']} 成功, {s['fail']} 失败")
    print("=" * 60)


def _show_stats(stats, elapsed, count):
    rate = count / (elapsed + 0.001)
    for mode in sorted(stats.keys()):
        s = stats[mode]
        print(f"  [{mode}] {s['ok']} ok / {s['fail']} fail  "
              f"({rate:.1f} files/min)")


if __name__ == "__main__":
    main()
