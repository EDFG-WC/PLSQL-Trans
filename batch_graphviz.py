#!/usr/bin/env python3
"""Batch Graphviz generator for all PL/SQL files in data/."""

import argparse, os, sys, time, re
from pathlib import Path
from plsql_graphviz import (
    PlSqlParser, CallGraphBuilder, FlowchartBuilder,
    DotGenerator, GraphvizRenderer
)

PROJECT_DIR = Path(__file__).parent.resolve()
DATA_DIR = PROJECT_DIR / "data"
OUTPUT_DIR = PROJECT_DIR / "output" / "graphviz"

SKIP_EXT = {".png", ".jpg", ".gif", ".zip", ".jar", ".class", ".pyc"}
MIN_SIZE, MAX_SIZE = 20, 5 * 1024 * 1024


def find_plsql_files(categories=None, max_files=None):
    files = []
    for root, dirs, filenames in os.walk(DATA_DIR):
        dirs[:] = [d for d in dirs if not d.startswith('.') and d != '__pycache__']
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
            try:
                with open(fp, 'rb') as f:
                    f.read(256).decode('utf-8')
            except (UnicodeDecodeError, OSError):
                continue
            files.append((fp, category))
    files.sort(key=lambda x: (x[1], str(x[0])))
    return files[:max_files] if max_files else files


def process_file(src_path, category, mode, formats):
    rel = src_path.relative_to(DATA_DIR)
    out_base = OUTPUT_DIR / rel.parent
    stem = src_path.stem
    results = []

    try:
        source = src_path.read_text(encoding='utf-8', errors='replace')
    except Exception as e:
        return [("error", f"read: {e}")]

    if not source.strip():
        return [("skip", "empty")]

    renderer = GraphvizRenderer()
    can_render = renderer.is_available()

    # Parse
    plsql_parser = PlSqlParser(source)
    procedures = plsql_parser.parse()

    if not procedures:
        return [("skip", "no procs")]

    out_base.mkdir(parents=True, exist_ok=True)

    # Master call graph
    if mode in ("master", "both"):
        try:
            builder = CallGraphBuilder(procedures, stem)
            dot_source = builder.build_dot()
            dot_path = out_base / f"{stem}.master.dot"
            dot_path.write_text(dot_source, encoding='utf-8')
            results.append(("master_dot", str(dot_path)))
            if can_render:
                for fmt in formats:
                    img_path = out_base / f"{stem}.master.{fmt}"
                    if renderer.render(dot_source, str(img_path), fmt):
                        results.append((f"master_{fmt}", str(img_path)))
        except Exception as e:
            results.append(("error", f"master: {e}"))

    # Detail flowcharts
    if mode in ("detail", "both"):
        try:
            for name, proc in procedures.items():
                safe_name = re.sub(r'[^A-Za-z0-9_]', '_', name)
                fb = FlowchartBuilder(proc)
                flow_graph = fb.build()
                dg = DotGenerator(flow_graph)
                dot_source = dg.generate()
                dot_path = out_base / f"{stem}.{safe_name}.dot"
                dot_path.write_text(dot_source, encoding='utf-8')
                results.append(("detail_dot", str(dot_path)))
                if can_render:
                    for fmt in formats:
                        img_path = out_base / f"{stem}.{safe_name}.{fmt}"
                        if renderer.render(dot_source, str(img_path), fmt):
                            results.append((f"detail_{fmt}", str(img_path)))
        except Exception as e:
            results.append(("error", f"detail: {e}"))

    return results


def main():
    parser = argparse.ArgumentParser(description="PL/SQL -> Graphviz batch")
    parser.add_argument("--categories", nargs="+", default=None,
                        help="Filter by category: PROCEDURE FUNCTION PACKAGE_BODY...")
    parser.add_argument("--max", type=int, default=None, help="Max files")
    parser.add_argument("--mode", default="both",
                        choices=["master", "detail", "both"])
    parser.add_argument("--format", default="png,svg", help="Output formats")
    parser.add_argument("--start-from", type=int, default=0)
    parser.add_argument("--single", type=str, default=None, help="Single file mode")
    args = parser.parse_args()

    formats = [f.strip() for f in args.format.split(",")]

    if args.single:
        src_path = Path(args.single)
        if not src_path.exists():
            print(f"File not found: {src_path}")
            sys.exit(1)
        print(f"Processing: {src_path}")
        results = process_file(src_path, "SINGLE", args.mode, formats)
        for kind, path in results:
            print(f"  {kind}: {path}")
        return

    files = find_plsql_files(args.categories, args.max)
    total = len(files)
    if total == 0:
        print("No PL/SQL files found")
        return

    print(f"Found {total} files")
    print(f"Mode: {args.mode}  Formats: {formats}")
    print(f"Output: {OUTPUT_DIR}\n")

    ok = fail = skip = 0
    t0 = time.time()

    for idx, (src_path, category) in enumerate(files):
        if idx < args.start_from:
            continue
        rel = src_path.relative_to(DATA_DIR)
        elapsed = time.time() - t0
        rate = (idx + 1 - args.start_from) / (elapsed + 0.001)
        eta = (total - idx - 1) / (rate + 0.001)
        print(f"[{idx+1}/{total}] {rel} ETA {eta/60:.0f}m", end="", flush=True)
        results = process_file(src_path, category, args.mode, formats)
        has_ok = any(not r[0].startswith("error") and not r[0].startswith("skip") for r in results)
        has_err = any(r[0] == "error" for r in results)
        if has_ok:
            ok += 1
            print(" OK")
        elif has_err:
            fail += 1
            print(" FAIL")
        else:
            skip += 1
            print(" skip")

    elapsed = time.time() - t0
    print(f"\nDone in {elapsed/60:.1f}m  OK={ok} FAIL={fail} SKIP={skip}")
    print(f"Output: {OUTPUT_DIR}")


if __name__ == "__main__":
    main()
