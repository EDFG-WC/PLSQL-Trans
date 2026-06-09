#!/usr/bin/env python3
"""
PL/SQL → Graphviz 图表生成器。

从 PL/SQL 源码生成两类 Graphviz DOT 图：
  1. 总图 (call graph) — 文件内所有过程/函数的调用关系图
  2. 分图 (flowchart)  — 每个过程/函数的详细控制流图

用法:
  python3 plsql_graphviz.py input.sql                    # 生成 DOT + PNG
  python3 plsql_graphviz.py input.sql --mode master      # 只生成总图
  python3 plsql_graphviz.py input.sql --mode detail      # 只生成分图
  python3 plsql_graphviz.py input.sql --format svg       # 输出 SVG
  python3 plsql_graphviz.py input.sql --output-dir ./out # 指定输出目录
"""

import argparse
import os
import re
import subprocess
import sys
import textwrap
from collections import OrderedDict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple


# ═══════════════════════════════════════════════════════════════════════
# Data models
# ═══════════════════════════════════════════════════════════════════════

@dataclass
class Procedure:
    """A PL/SQL procedure or function declaration."""
    name: str
    kind: str  # 'PROCEDURE' or 'FUNCTION'
    body: str
    start_line: int
    end_line: int
    calls: List[str] = field(default_factory=list)


@dataclass
class FlowNode:
    """A node in a control-flow graph."""
    id: str
    label: str
    node_type: str  # 'start','end','process','decision','loop','exception','merge','return'
    extra: str = ""


@dataclass
class FlowEdge:
    """An edge in a control-flow graph."""
    from_id: str
    to_id: str
    label: str = ""


@dataclass
class FlowGraph:
    """Control-flow graph for a procedure/function."""
    proc_name: str
    nodes: List[FlowNode] = field(default_factory=list)
    edges: List[FlowEdge] = field(default_factory=list)
    next_id: int = 1

    def add_node(self, node_type: str, label: str, extra: str = "") -> str:
        nid = f"n{self.next_id}"
        self.next_id += 1
        self.nodes.append(FlowNode(nid, label, node_type, extra))
        return nid

    def add_edge(self, from_id: str, to_id: str, label: str = ""):
        self.edges.append(FlowEdge(from_id, to_id, label))


# ═══════════════════════════════════════════════════════════════════════
# PL/SQL Parser (regex-based)
# ═══════════════════════════════════════════════════════════════════════

class PlSqlParser:
    """Parse PL/SQL source to extract procedures, functions, and their calls."""

    SQL_KEYWORDS = {
        'SELECT', 'INSERT', 'UPDATE', 'DELETE', 'MERGE', 'CREATE', 'DROP',
        'ALTER', 'TRUNCATE', 'GRANT', 'REVOKE', 'COMMIT', 'ROLLBACK',
        'SAVEPOINT', 'SET', 'DECLARE', 'BEGIN', 'END', 'EXCEPTION',
        'IF', 'ELSE', 'ELSIF', 'LOOP', 'FOR', 'WHILE', 'EXIT',
        'RETURN', 'GOTO', 'NULL', 'CASE', 'WHEN', 'THEN',
        'OPEN', 'CLOSE', 'FETCH', 'EXECUTE', 'CALL',
        'PRAGMA', 'RAISE', 'PACKAGE', 'TYPE', 'IS', 'AS',
        'AND', 'OR', 'NOT', 'IN', 'OUT', 'NOCOPY',
        'DBMS_OUTPUT', 'DBMS_LOB', 'UTL_FILE', 'UTL_RAW', 'UTL_ENCODE',
        'PUT_LINE', 'GET_LINE', 'CREATETEMPORARY', 'FILEGETNAME',
        'BFILENAME', 'SUBSTR', 'INSTR', 'LENGTH', 'REPLACE', 'TRANSLATE',
        'TO_CHAR', 'TO_DATE', 'TO_NUMBER', 'NVL', 'COALESCE', 'DECODE',
        'UPPER', 'LOWER', 'INITCAP', 'LPAD', 'RPAD', 'LTRIM', 'RTRIM',
        'TRIM', 'CONCAT', 'CHR', 'ASCII', 'MOD', 'ROUND', 'TRUNC',
        'SYSDATE', 'SYSTIMESTAMP', 'CURRENT_DATE', 'CURRENT_TIMESTAMP',
        'TRUE', 'FALSE', 'COUNT', 'SUM', 'AVG', 'MIN', 'MAX',
        'EXISTS', 'BETWEEN', 'LIKE', 'ROWID', 'ROWNUM', 'LEVEL',
        'TABLE', 'VIEW', 'INDEX', 'SEQUENCE', 'SYNONYM',
        'COLUMN', 'CONSTRAINT', 'TRIGGER', 'SCHEMA', 'DATABASE',
        'CURSOR', 'RECORD', 'VARRAY', 'OBJECT', 'BODY', 'EXCEPTION_INIT',
        'BULK', 'COLLECT', 'LIMIT', 'ALL', 'ANY', 'SOME',
        'CONNECT', 'BY', 'START', 'WITH', 'PRIOR', 'SIBLINGS',
        'UNION', 'INTERSECT', 'MINUS', 'JOIN', 'ON', 'USING',
        'GROUP', 'HAVING', 'ORDER', 'ASC', 'DESC', 'DISTINCT',
        'FROM', 'WHERE', 'INTO', 'VALUES',
        'CAST', 'MULTISET', 'TREAT', 'REF', 'DEREF', 'VALUE',
        'CONTINUE', 'OTHERS', 'SQLERRM', 'SQLCODE',
        'NO_DATA_FOUND', 'TOO_MANY_ROWS', 'DUP_VAL_ON_INDEX',
        'ZERO_DIVIDE', 'INVALID_NUMBER', 'VALUE_ERROR',
        'CHAR', 'VARCHAR2', 'NUMBER', 'INTEGER', 'PLS_INTEGER',
        'BINARY_INTEGER', 'BOOLEAN', 'DATE', 'TIMESTAMP', 'CLOB',
        'BLOB', 'RAW', 'LONG', 'ROWID', 'UROWID', 'BFILE',
    }

    def __init__(self, source: str):
        self.source = source
        self.procedures: Dict[str, Procedure] = OrderedDict()

    def parse(self) -> Dict[str, Procedure]:
        self._find_procedures()
        self._find_calls()
        return self.procedures

    def _find_procedures(self):
        """Find all PROCEDURE/FUNCTION declarations and extract bodies."""
        pattern = re.compile(
            r'(?:^|\n\s*|;\s*)'                             # start of line / after semicolon
            r'(?:CREATE\s+(?:OR\s+REPLACE\s+)?)?'           # optional CREATE [OR REPLACE]
            r'(PROCEDURE|FUNCTION)\s+'                       # keyword
            r'("?[A-Za-z_]\w*"?)\s*',                        # name
            re.IGNORECASE | re.MULTILINE
        )

        for match in pattern.finditer(self.source):
            kind = match.group(1).upper()
            name = match.group(2).strip('"')
            start_pos = match.start()

            body = self._extract_body(self.source, start_pos, name, kind)
            if body is None:
                continue

            start_line = self.source[:start_pos].count('\n') + 1
            end_line = self.source[:start_pos + len(body)].count('\n') + 1

            self.procedures[name.upper()] = Procedure(
                name=name, kind=kind, body=body,
                start_line=start_line, end_line=end_line,
            )

    def _extract_body(self, source: str, start: int, name: str, kind: str) -> Optional[str]:
        """Extract the full body from declaration start to matching END [name];"""
        # Find IS/AS keyword
        rest = source[start:]
        is_match = re.search(r'\b(IS|AS)\b', rest, re.IGNORECASE)
        if not is_match:
            return None

        body_start = start + is_match.end()
        rest = source[body_start:]

        # Walk to find matching END, tracking BEGIN/END nesting
        depth = 0
        i = 0
        n = len(rest)

        while i < n:
            # Skip strings
            if rest[i] == "'":
                i += 1
                while i < n:
                    if rest[i] == "'":
                        if i + 1 < n and rest[i + 1] == "'":
                            i += 2
                            continue
                        i += 1
                        break
                    i += 1
                continue

            # Skip line comments
            if rest[i:i + 2] == '--':
                while i < n and rest[i] != '\n':
                    i += 1
                continue

            # Skip block comments
            if rest[i:i + 2] == '/*':
                i += 2
                while i < n - 1:
                    if rest[i:i + 2] == '*/':
                        i += 2
                        break
                    i += 1
                continue

            # Read word
            word = self._read_word(rest, i)
            if word:
                uw = word.upper()

                if uw == 'END':
                    # Peek ahead: END IF / END LOOP / END CASE?
                    after = rest[i + len(word):]
                    next_word_match = re.match(r'\s+([A-Za-z_]\w*)', after, re.IGNORECASE)
                    next_word = next_word_match.group(1).upper() if next_word_match else None
                    if next_word in ('IF', 'LOOP', 'CASE'):
                        # END IF / END LOOP / END CASE → close one block
                        depth -= 1
                        i += len(word) + (next_word_match.end() - next_word_match.start())
                    else:
                        depth -= 1
                        i += len(word)
                    if depth <= 0:
                        # Found matching END — read past optional name and semicolon
                        end_pos = body_start + i
                        # Skip optional name after END
                        after_end = source[end_pos:].lstrip()
                        name_pat = re.match(r'("?[A-Za-z_]\w*"?)?', after_end, re.IGNORECASE)
                        skip = len(source[end_pos:]) - len(after_end)
                        if name_pat and name_pat.group(1):
                            skip += name_pat.end()
                        after_name = source[end_pos + skip:].lstrip()
                        if after_name.startswith(';'):
                            skip += len(source[end_pos + skip:]) - len(after_name) + 1
                        return source[start:end_pos + skip]
                    continue

                elif uw == 'BEGIN':
                    depth += 1
                elif uw == 'IF':
                    depth += 1
                elif uw == 'LOOP':
                    depth += 1
                elif uw == 'CASE':
                    depth += 1

                i += len(word)
            else:
                i += 1

        return source[start:]

    def _read_word(self, s: str, i: int) -> Optional[str]:
        """Read an identifier at position i."""
        if i >= len(s):
            return None
        if s[i] == '"':
            j = i + 1
            while j < len(s) and s[j] != '"':
                j += 1
            return s[i:j + 1] if j < len(s) else None
        if not (s[i].isalpha() or s[i] == '_'):
            return None
        j = i
        while j < len(s) and (s[j].isalnum() or s[j] in '_$#'):
            j += 1
        return s[i:j]

    def _find_calls(self):
        """Find calls between procedures/functions within the file."""
        all_names = {p.name.upper() for p in self.procedures.values()}
        all_names_lower = {n.lower() for n in all_names}

        for proc in self.procedures.values():
            # In the body, find calls to known procedures
            body = proc.body
            for target_name in all_names:
                if target_name == proc.name.upper():
                    continue  # skip self-calls for now
                # Look for target_name followed by ( or space then (
                pattern = re.compile(
                    r'\b' + re.escape(target_name) + r'\s*\(',
                    re.IGNORECASE
                )
                if pattern.search(body):
                    proc.calls.append(target_name)


# ═══════════════════════════════════════════════════════════════════════
# Call Graph Builder (总图)
# ═══════════════════════════════════════════════════════════════════════

class CallGraphBuilder:
    """Build master call-graph DOT showing inter-procedure relationships."""

    def __init__(self, procedures: Dict[str, Procedure], filename: str = ""):
        self.procedures = procedures
        self.filename = filename

    def validate(self) -> dict:
        """Validate call graph. Returns {warnings:[], errors:[]}."""
        issues = {'warnings': [], 'errors': []}
        all_names = {n.upper() for n in self.procedures.keys()}
        called_by = {}
        for proc in self.procedures.values():
            for c in proc.calls:
                called_by.setdefault(c, []).append(proc.name.upper())
        for name, proc in self.procedures.items():
            for callee in proc.calls:
                if callee not in all_names:
                    issues['warnings'].append(
                        f'{proc.kind} {proc.name} calls unknown: {callee}')
            if name.upper() in proc.calls:
                issues['warnings'].append(
                    f'{proc.kind} {proc.name} calls itself (recursion)')
        called = {n for n in all_names if n in called_by}
        callers = {n for n in all_names
                   if any(n in p.calls for p in self.procedures.values())}
        isolated = all_names - called - callers
        if isolated:
            issues['warnings'].append(
                f'{len(isolated)} isolated procedures (no calls in/out)')
        entries = all_names - called
        if not entries:
            issues['errors'].append('No entry points found!')
        return issues

    def build_dot(self) -> str:
        if not self.procedures:
            return self._empty_dot()

        all_names = {p.name.upper() for p in self.procedures.values()}

        # Build reverse call map: callee → [callers]
        called_by: Dict[str, List[str]] = {}
        for proc in self.procedures.values():
            src = proc.name.upper()
            for callee in proc.calls:
                called_by.setdefault(callee, []).append(src)

        # Categorize: entry, mid, leaf, orphan
        # - entry: not called by anyone in this file, but calls others
        # - mid: called by someone, and calls others
        # - leaf: called by someone, no outgoing calls to known procedures
        # - orphan: not called, no outgoing calls (isolated)
        # Also: if only 1 proc, it's an entry (not orphan)
        entry_procs = []
        mid_procs = []
        leaf_procs = []
        orphan_procs = []

        for name in sorted(all_names):
            proc = self.procedures.get(name)
            is_called = name in called_by
            has_calls = bool(proc and proc.calls)

            if not is_called and has_calls:
                entry_procs.append(name)
            elif is_called and has_calls:
                mid_procs.append(name)
            elif is_called and not has_calls:
                leaf_procs.append(name)
            else:
                # Not called, no calls
                if len(all_names) == 1:
                    entry_procs.append(name)  # single proc → it IS the entry
                else:
                    orphan_procs.append(name)

        lines = []
        self._dot_header(lines)

        # ── END node ──
        lines.append('  // ── END node ──')
        lines.append('  end_node [label="END" shape=ellipse '
                     'fillcolor="#909399" fontcolor="#ffffff" fontsize=12 penwidth=2];')
        lines.append('')

        # ── Entry points (implicit START) ──
        lines.append('  // ── Entry points ──')
        for name in entry_procs:
            lines.append(f'  {self._nid(name)} [label="{self._label(name)}" '
                         f'fillcolor="#4a6fa5" fontcolor="#ffffff"];')
        lines.append('')

        # ── Intermediate ──
        if mid_procs:
            lines.append('  // ── Intermediate ──')
            for name in mid_procs:
                lines.append(f'  {self._nid(name)} [label="{self._label(name)}" '
                             f'fillcolor="#6b4c8a" fontcolor="#ffffff"];')
            lines.append('')

        # ── Leaf ──
        if leaf_procs:
            lines.append('  // ── Leaf (no outgoing calls) ──')
            for name in leaf_procs:
                lines.append(f'  {self._nid(name)} [label="{self._label(name)}" '
                             f'fillcolor="#e6a23c" fontcolor="#ffffff"];')
            lines.append('')

        # ── Edges: START → entry ──
        lines.append('  // ── Edges ──')

        # ── Call edges ──
        for proc in self.procedures.values():
            src = proc.name.upper()
            for callee in proc.calls:
                if callee in all_names:
                    lines.append(f'  {self._nid(src)} -> {self._nid(callee)};')


        # ── Endpoints → END ──
        endpoints = set()
        for proc in self.procedures.values():
            name = proc.name.upper()
            resolved = [c for c in proc.calls if c in all_names]
            if not resolved and name not in orphan_procs:
                endpoints.add(name)

        for name in sorted(endpoints):
            lines.append(f'  {self._nid(name)} -> end_node;')
        lines.append('')


        # ── Orphans: not in graph, listed as comment ──
        if orphan_procs:
            names = ', '.join(orphan_procs)
            lines.append(f'  // Orphans (not called, not in graph): {names}')

        lines.append('}')
        return '\n'.join(lines)

    def _dot_header(self, lines: List[str]):
        lines.append(f'// Master Call Graph — {self.filename}')
        lines.append(f'// Procedures: {len(self.procedures)}')
        lines.append('digraph master_call_graph {')
        lines.append('  rankdir=LR; fontname="Helvetica,Arial,sans-serif"; fontsize=10;')
        lines.append('  bgcolor="#f8f9fa"; pad=0.3; nodesep=0.18; ranksep=0.45;')
        lines.append('  ratio=compress;')
        lines.append('  splines=polyline; compound=true; newrank=true; ordering=out;')
        lines.append('  ratio=compress;')
        lines.append('  node [fontname="Helvetica,Arial,sans-serif" fontsize=8 '
                     'shape=box style="filled,rounded" penwidth=1.0 margin="0.05,0.03"];')
        lines.append('  edge [fontname="Helvetica,Arial,sans-serif" fontsize=8 '
                     'color="#5c5c7a" penwidth=0.6 arrowsize=0.7];')
        lines.append('')

    def _nid(self, name: str) -> str:
        return 'p_' + re.sub(r'[^A-Za-z0-9_]', '_', name)

    def _label(self, name: str) -> str:
        proc = self.procedures.get(name)
        if proc:
            abbr = 'P' if proc.kind == 'PROCEDURE' else 'F'
            return f"{abbr}: {proc.name}"
        return name

    def _empty_dot(self) -> str:
        return 'digraph empty { label="No procedures found"; }'


# ═══════════════════════════════════════════════════════════════════════
# Flowchart Builder (分图) — detailed control flow per procedure
# ═══════════════════════════════════════════════════════════════════════

class FlowchartBuilder:
    """Build detailed control-flow graph for a single procedure/function."""

    # Block-level keywords that change nesting depth
    BLOCK_OPEN = {'BEGIN', 'IF', 'LOOP', 'CASE'}
    BLOCK_CLOSE_COMPOUND = {'END IF', 'END LOOP', 'END CASE'}
    BLOCK_CLOSE = {'END'}

    def __init__(self, proc: Procedure):
        self.proc = proc

    def build(self) -> FlowGraph:
        """Build the control-flow graph."""
        g = FlowGraph(proc_name=self.proc.name)
        body = self.proc.body

        # Find the BEGIN keyword
        begin_pos = self._find_keyword(body, 'BEGIN')
        if begin_pos < 0:
            return g

        # Find the executable section boundaries
        after_begin = body[begin_pos + len('BEGIN'):]  # skip 'BEGIN' keyword
        # Find matching END
        end_pos = self._find_matching_end(after_begin)
        if end_pos < 0:
            end_pos = len(after_begin)

        inner_body = after_begin[:end_pos]  # content between BEGIN and END

        # Split into main body and exception section
        # (EXCEPTION is at the outermost level, depth 0 in this context)
        main_body, exc_body = self._split_exception(inner_body)

        # Start node
        abbr = 'P' if self.proc.kind == 'PROCEDURE' else 'F'
        start_id = g.add_node('start', f"{abbr}: {self.proc.name}")

        # BEGIN marker
        begin_id = g.add_node('process', 'BEGIN')
        g.add_edge(start_id, begin_id)

        # Process main body
        last_id = self._walk_and_build(g, begin_id, main_body)

        # Exception section
        if exc_body:
            exc_node = g.add_node('exception', 'EXCEPTION')
            g.add_edge(last_id, exc_node)
            last_id = self._process_exceptions(g, exc_node, exc_body)

        # End node
        end_id = g.add_node('end', f"END {self.proc.name}")
        g.add_edge(last_id, end_id)

        return g

    # ── Text navigation helpers ──

    def _find_keyword(self, text: str, keyword: str) -> int:
        """Find a keyword, skipping strings and comments."""
        i = 0
        n = len(text)
        while i < n:
            i = self._skip_junk(text, i)
            if i >= n:
                break
            word = self._read_word(text, i)
            if word and word.upper() == keyword.upper():
                return i
            i += 1 if not word else len(word)
        return -1

    def _find_matching_end(self, text: str) -> int:
        """Find matching END for the outermost block.
        Assumes text does NOT start with BEGIN (BEGIN was already stripped).
        Returns position of the END that closes the outermost block."""
        depth = 0
        i = 0
        n = len(text)
        while i < n:
            i = self._skip_junk(text, i)
            if i >= n:
                break
            word, skip = self._read_compound_word(text, i)
            if word:
                uw = word.upper()
                if uw in self.BLOCK_OPEN:
                    depth += 1
                elif uw in self.BLOCK_CLOSE_COMPOUND or uw in self.BLOCK_CLOSE:
                    depth -= 1
                if depth < 0:
                    return i
                i += skip
            else:
                i += 1
        return len(text)

    def _split_exception(self, text: str) -> Tuple[str, str]:
        """Split text at EXCEPTION keyword at depth 0."""
        depth = 0
        i = 0
        n = len(text)
        while i < n:
            i = self._skip_junk(text, i)
            if i >= n:
                break
            word, skip = self._read_compound_word(text, i)
            if word:
                uw = word.upper()
                if uw in self.BLOCK_OPEN:
                    depth += 1
                elif uw in self.BLOCK_CLOSE_COMPOUND:
                    depth -= 1
                elif uw in self.BLOCK_CLOSE:
                    depth -= 1
                elif uw == 'EXCEPTION' and depth == 0:
                    return text[:i], text[i:]
                i += skip
            else:
                i += 1
        return text, ""

    def _read_compound_word(self, s: str, i: int) -> Tuple[Optional[str], int]:
        """Read a word at position i. For END, peek ahead for IF/LOOP/CASE.
        Returns (compound_word, chars_to_skip)."""
        word = self._read_word(s, i)
        if not word:
            return None, 0

        uw = word.upper()
        if uw == 'END':
            # Peek ahead for IF / LOOP / CASE
            after = s[i + len(word):]
            pm = re.match(r'\s+([A-Za-z_]\w*)', after, re.IGNORECASE)
            if pm and pm.group(1).upper() in ('IF', 'LOOP', 'CASE'):
                compound = f"END {pm.group(1).upper()}"
                skip = len(word) + pm.end()  # pm.end() already includes the keyword after END
                return compound, skip

        return word, len(word)

    def _read_word(self, s: str, i: int) -> Optional[str]:
        """Read an identifier at position i."""
        if i >= len(s):
            return None
        if s[i] == '"':
            j = i + 1
            while j < len(s) and s[j] != '"':
                j += 1
            return s[i:j + 1] if j < len(s) else None
        if not (s[i].isalpha() or s[i] == '_'):
            return None
        j = i
        while j < len(s) and (s[j].isalnum() or s[j] in '_$#'):
            j += 1
        return s[i:j]

    def _skip_junk(self, s: str, i: int) -> int:
        """Skip strings and comments; return new position."""
        n = len(s)
        if i >= n:
            return i
        if s[i] == "'":
            i += 1
            while i < n:
                if s[i] == "'":
                    if i + 1 < n and s[i + 1] == "'":
                        i += 2
                        continue
                    return i + 1
                i += 1
            return n
        if s[i:i + 2] == '--':
            while i < n and s[i] != '\n':
                i += 1
            return i
        if s[i:i + 2] == '/*':
            i += 2
            while i < n - 1:
                if s[i:i + 2] == '*/':
                    return i + 2
                i += 1
            return n
        return i

    # ── Flow graph building ──

    def _walk_and_build(self, g: FlowGraph, entry_id: str, text: str) -> str:
        """Walk through body text and build flow graph incrementally.
        Returns the ID of the last node added."""
        if not text.strip():
            return entry_id

        # Split into top-level statements (on ; at depth 0)
        statements = self._split_top_level(text)
        if not statements:
            return entry_id

        last_id = entry_id
        i = 0
        while i < len(statements):
            stmt = statements[i].strip()
            if not stmt:
                i += 1
                continue

            ctrl = self._classify_statement(stmt, statements, i)

            if ctrl == 'IF':
                end_i, new_last = self._build_if(g, last_id, statements, i)
                i = end_i + 1
                last_id = new_last
            elif ctrl == 'LOOP':
                end_i, new_last = self._build_loop(g, last_id, statements, i, 'LOOP')
                i = end_i + 1
                last_id = new_last
            elif ctrl == 'FOR':
                end_i, new_last = self._build_loop(g, last_id, statements, i, 'FOR')
                i = end_i + 1
                last_id = new_last
            elif ctrl == 'WHILE':
                end_i, new_last = self._build_loop(g, last_id, statements, i, 'WHILE')
                i = end_i + 1
                last_id = new_last
            elif ctrl == 'RETURN':
                nid = g.add_node('return', self._shorten(stmt, 40))
                g.add_edge(last_id, nid)
                last_id = nid
                i += 1
            else:
                short = self._shorten(stmt, 55)
                if short:
                    nid = g.add_node('process', short)
                    g.add_edge(last_id, nid)
                    last_id = nid
                i += 1

        return last_id

    def _split_top_level(self, text: str) -> List[str]:
        """Split text into top-level statements on ; at depth 0.
        Respects strings, comments, and nested blocks."""
        stmts = []
        depth = 0
        buf = []
        i = 0
        n = len(text)

        while i < n:
            ch = text[i]

            # Strings
            if ch == "'":
                buf.append(ch); i += 1
                while i < n:
                    if text[i] == "'":
                        if i + 1 < n and text[i + 1] == "'":
                            buf.append("''"); i += 2; continue
                        buf.append("'"); i += 1; break
                    buf.append(text[i]); i += 1
                continue

            # Line comment
            if ch == '-' and i + 1 < n and text[i + 1] == '-':
                while i < n and text[i] != '\n':
                    buf.append(text[i]); i += 1
                continue

            # Block comment
            if ch == '/' and i + 1 < n and text[i + 1] == '*':
                buf.append('/*'); i += 2
                while i < n - 1:
                    if text[i:i + 2] == '*/':
                        buf.append('*/'); i += 2; break
                    buf.append(text[i]); i += 1
                continue

            # Track depth with compound keywords
            word, skip = self._read_compound_word(text, i)
            if word:
                uw = word.upper()
                if uw in self.BLOCK_OPEN:
                    depth += 1
                elif uw in self.BLOCK_CLOSE_COMPOUND:
                    depth -= 1
                elif uw in self.BLOCK_CLOSE:
                    depth -= 1
                # Append the whole compound word to buf and skip its characters
                for _ in range(skip):
                    if i < n:
                        buf.append(text[i])
                        i += 1
                continue

            # Split on ; at depth 0
            if ch == ';' and depth <= 0:
                s = ''.join(buf).strip()
                if s:
                    stmts.append(s)
                buf = []
                i += 1
            else:
                buf.append(ch)
                i += 1

        s = ''.join(buf).strip()
        if s:
            stmts.append(s)
        return stmts

    def _classify_statement(self, stmt: str, all_stmts: List[str], idx: int) -> str:
        """Classify a statement's control type."""
        upper = stmt.upper().strip()
        # Check first word
        first_word = upper.split()[0] if upper.split() else upper
        if first_word == 'IF':
            return 'IF'
        if first_word == 'LOOP':
            return 'LOOP'
        if first_word == 'FOR':
            return 'FOR'
        if first_word == 'WHILE':
            return 'WHILE'
        if first_word == 'RETURN':
            return 'RETURN'
        return 'PROCESS'

    def _find_block_end(self, stmts: List[str], start_idx: int,
                         open_kw: str, close_kw: str) -> int:
        """Find matching END for a block starting at start_idx."""
        depth = 1
        for j in range(start_idx + 1, len(stmts)):
            s = stmts[j].upper().strip()
            first = s.split()[0] if s.split() else s
            if first == open_kw:
                depth += 1
            elif first == 'END':
                # Check if it's END IF / END LOOP etc.
                parts = s.split()
                if len(parts) >= 2 and parts[1] == close_kw:
                    depth -= 1
                elif open_kw in ('FOR', 'WHILE') and close_kw == 'LOOP':
                    depth -= 1  # END LOOP closes FOR/WHILE too
                elif open_kw == 'LOOP' and close_kw == 'LOOP':
                    depth -= 1
            if depth == 0:
                return j
        return len(stmts) - 1

    def _build_if(self, g: FlowGraph, entry_id: str, stmts: List[str],
                   idx: int) -> Tuple[int, str]:
        """Build IF/ELSIF/ELSE/END IF subgraph from a single top-level IF statement.
        Returns (end_idx, last_node_id)."""
        stmt = stmts[idx]
        # Extract condition from IF ... THEN
        cond = self._extract_condition(stmt)
        dec_id = g.add_node('decision', f"IF {self._shorten(cond, 28)}")
        g.add_edge(entry_id, dec_id)

        # Split the IF statement text into branches (THEN, ELSIF, ELSE)
        branches = self._split_if_text(stmt)

        merge_id = g.add_node('merge', '')
        branch_exits = []
        has_else = any(br['kind'] == 'ELSE' for br in branches)

        for br in branches:
            if br['kind'] == 'THEN':
                body_id = g.add_node('process', 'THEN')
                g.add_edge(dec_id, body_id, 'Y')
                last = self._walk_and_build(g, body_id, br['body'])
                branch_exits.append(last)
                if not has_else and len(branches) == 1:
                    g.add_edge(dec_id, merge_id, 'N')
            elif br['kind'] == 'ELSIF':
                e_cond = self._extract_condition(br['header'])
                e_dec = g.add_node('decision', f"ELSIF {self._shorten(e_cond, 25)}")
                g.add_edge(dec_id, e_dec, 'N')
                body_id = g.add_node('process', 'THEN')
                g.add_edge(e_dec, body_id, 'Y')
                last = self._walk_and_build(g, body_id, br['body'])
                branch_exits.append(last)
                dec_id = e_dec  # chain further ELSIF/ELSE from here
            elif br['kind'] == 'ELSE':
                body_id = g.add_node('process', 'ELSE')
                g.add_edge(dec_id, body_id, 'N')
                last = self._walk_and_build(g, body_id, br['body'])
                branch_exits.append(last)

        if not has_else and len(branches) > 1:
            g.add_edge(dec_id, merge_id, 'N')

        # All branches converge
        for be in branch_exits:
            g.add_edge(be, merge_id)

        return idx, merge_id

    def _split_if_text(self, if_stmt: str) -> List[dict]:
        """Split an IF statement text into THEN/ELSIF/ELSE branches.
        Each branch has: kind, header (for ELSIF), body (text)."""
        branches = []
        # Remove IF prefix to find THEN
        # Find THEN keyword
        then_match = re.search(r'\bTHEN\b', if_stmt, re.IGNORECASE)
        if not then_match:
            return branches

        after_if = if_stmt[then_match.end():]

        # Split after_if into segments at ELSIF / ELSE / END IF boundaries
        # Walk through at depth 0 to find branch boundaries
        depth = 0
        segments = []  # [(kind, text)]
        buf = []
        i = 0
        n = len(after_if)
        current_kind = 'THEN'

        while i < n:
            i = self._skip_junk(after_if, i)
            if i >= n:
                break
            word, skip = self._read_compound_word(after_if, i)
            if word:
                uw = word.upper()
                if uw in self.BLOCK_OPEN:
                    depth += 1
                    for _ in range(skip):
                        if i < n:
                            buf.append(after_if[i])
                            i += 1
                    continue
                elif uw in self.BLOCK_CLOSE_COMPOUND or uw in self.BLOCK_CLOSE:
                    depth -= 1
                    if uw == 'END IF':
                        if depth < 0:
                            # This is the END IF of our IF block
                            segments.append({'kind': current_kind, 'body': ''.join(buf).strip()})
                            buf = []
                            break
                    for _ in range(skip):
                        if i < n:
                            buf.append(after_if[i])
                            i += 1
                    continue
                elif depth == 0:
                    if uw == 'ELSIF':
                        segments.append({'kind': current_kind, 'body': ''.join(buf).strip()})
                        buf = []
                        # Read the ELSIF header (condition + THEN)
                        header_start = i
                        # Find THEN for this ELSIF
                        remaining = after_if[i:]
                        elsif_then = re.search(r'\bTHEN\b', remaining, re.IGNORECASE)
                        if elsif_then:
                            header_end = i + elsif_then.end()
                            current_kind = 'ELSIF'
                            header = after_if[i:header_end]
                            segments.append({'kind': 'ELSIF_HEADER', 'header': header})
                            i = header_end
                            continue
                    elif uw == 'ELSE':
                        segments.append({'kind': current_kind, 'body': ''.join(buf).strip()})
                        buf = []
                        current_kind = 'ELSE'
                        i += skip
                        continue
            buf.append(after_if[i])
            i += 1

        if buf:
            segments.append({'kind': current_kind, 'body': ''.join(buf).strip()})

        # Convert segments to branches
        branches = []
        current = {'kind': 'THEN', 'header': if_stmt[:then_match.end()], 'body': ''}
        for seg in segments:
            if seg['kind'] == 'ELSIF_HEADER':
                branches.append(current)
                current = {'kind': 'ELSIF', 'header': seg.get('header', ''), 'body': ''}
            elif seg['kind'] == 'ELSIF':
                current['body'] = seg['body']
                branches.append(current)
                current = {'kind': 'ELSIF', 'header': '', 'body': ''}
            elif seg['kind'] == 'ELSE':
                branches.append(current)
                current = {'kind': 'ELSE', 'header': '', 'body': seg['body']}
            else:  # THEN
                current['body'] = seg['body']
        branches.append(current)

        return branches

    def _build_loop(self, g: FlowGraph, entry_id: str, stmts: List[str],
                     idx: int, loop_type: str) -> Tuple[int, str]:
        """Build LOOP / FOR / WHILE subgraph from a single top-level statement."""
        stmt = stmts[idx]
        if loop_type == 'LOOP':
            label = 'LOOP'
        elif loop_type == 'FOR':
            cond = self._extract_condition(stmt)
            label = f"FOR {self._shorten(cond, 25)}"
        else:  # WHILE
            cond = self._extract_condition(stmt)
            label = f"WHILE {self._shorten(cond, 22)}"

        loop_id = g.add_node('loop', label)
        g.add_edge(entry_id, loop_id)

        # Extract loop body from the statement text
        loop_body = self._extract_loop_body(stmt, loop_type)

        body_entry = g.add_node('process', 'loop body')
        g.add_edge(loop_id, body_entry)
        body_end = self._walk_and_build(g, body_entry, loop_body)
        g.add_edge(body_end, loop_id, 'loop')

        # Exit path
        exit_id = g.add_node('merge', '')
        g.add_edge(loop_id, exit_id, 'exit')
        return idx, exit_id

    def _extract_loop_body(self, loop_stmt: str, loop_type: str) -> str:
        """Extract the body text between LOOP keyword and END LOOP."""
        # Find the LOOP keyword (after FOR/WHILE condition if applicable)
        if loop_type in ('FOR', 'WHILE'):
            loop_match = re.search(r'\bLOOP\b', loop_stmt, re.IGNORECASE)
            if not loop_match:
                return ''
            after_loop = loop_stmt[loop_match.end():]
        else:
            # Basic LOOP: find first LOOP keyword
            loop_match = re.search(r'\bLOOP\b', loop_stmt, re.IGNORECASE)
            if not loop_match:
                return ''
            after_loop = loop_stmt[loop_match.end():]

        # Find END LOOP at depth 0
        depth = 0
        i = 0
        n = len(after_loop)
        while i < n:
            i = self._skip_junk(after_loop, i)
            if i >= n:
                break
            word, skip = self._read_compound_word(after_loop, i)
            if word:
                uw = word.upper()
                if uw in self.BLOCK_OPEN:
                    depth += 1
                elif uw in self.BLOCK_CLOSE_COMPOUND or uw in self.BLOCK_CLOSE:
                    depth -= 1
                if depth < 0:
                    # Found END LOOP
                    return after_loop[:i]
                i += skip
            else:
                i += 1
        return after_loop

    def _process_exceptions(self, g: FlowGraph, exc_entry: str, exc_text: str) -> str:
        """Process EXCEPTION section: WHEN ... THEN handlers."""
        # Split into WHEN handlers
        handlers = self._split_when_handlers(exc_text)
        if not handlers:
            return exc_entry

        # If there's a single handler or no handlers found,
        # show as single exception node
        if len(handlers) == 1:
            name = handlers[0]['name']
            body = handlers[0]['body']
            when_id = g.add_node('exception', f"WHEN {name}")
            g.add_edge(exc_entry, when_id)
            if body.strip():
                return self._walk_and_build(g, when_id, body)
            return when_id

        # Multiple handlers - chain from a decision
        merge_id = g.add_node('merge', '')
        prev_exit = None

        for i, h in enumerate(handlers):
            name = h['name']
            body = h['body']
            when_id = g.add_node('exception', f"WHEN {name}")
            g.add_edge(exc_entry, when_id, f"({i + 1})")
            if body.strip():
                last = self._walk_and_build(g, when_id, body)
                g.add_edge(last, merge_id)
            else:
                g.add_edge(when_id, merge_id)

        return merge_id

    def _split_when_handlers(self, exc_text: str) -> List[dict]:
        """Split exception section into individual WHEN ... handlers."""
        handlers = []
        # Find all WHEN keywords at depth 0
        pattern = re.compile(r'\bWHEN\b', re.IGNORECASE)
        positions = []
        i = 0
        n = len(exc_text)
        depth = 0
        while i < n:
            i = self._skip_junk(exc_text, i)
            if i >= n:
                break
            word, skip = self._read_compound_word(exc_text, i)
            if word:
                uw = word.upper()
                if uw in self.BLOCK_OPEN:
                    depth += 1
                elif uw in self.BLOCK_CLOSE_COMPOUND:
                    depth -= 1
                elif uw in self.BLOCK_CLOSE:
                    depth -= 1
                elif uw == 'WHEN' and depth == 0:
                    positions.append(i)
                i += skip
            else:
                i += 1

        for p_idx, pos in enumerate(positions):
            start = pos
            end = positions[p_idx + 1] if p_idx + 1 < len(positions) else len(exc_text)
            handler_text = exc_text[start:end].strip()

            # Extract exception name(s) after WHEN
            name_match = re.match(r'WHEN\s+(.+?)\s+THEN', handler_text,
                                   re.IGNORECASE | re.DOTALL)
            exc_name = name_match.group(1).strip() if name_match else 'OTHERS'
            # Shorten name
            exc_name = re.sub(r'\s+', ' ', exc_name)[:40]

            # Extract body after THEN
            if name_match:
                body = handler_text[name_match.end():]
            else:
                body = handler_text

            handlers.append({'name': exc_name, 'body': body})

        return handlers

    def _extract_condition(self, stmt: str) -> str:
        """Extract condition from IF/FOR/WHILE statement."""
        s = stmt.strip()
        # IF cond THEN ... → cond
        # FOR x IN ... LOOP → ...
        # WHILE cond LOOP → cond
        for kw in ['IF', 'FOR', 'WHILE', 'ELSIF']:
            m = re.match(rf'{kw}\s+(.+?)\s+(THEN|LOOP)', s, re.IGNORECASE | re.DOTALL)
            if m:
                return m.group(1).strip()
        return s[:50]

    def _shorten(self, text: str, max_len: int = 50) -> str:
        """Shorten text for display in a graph node."""
        text = text.strip()
        # Collapse whitespace
        text = re.sub(r'\s+', ' ', text)
        # Remove trailing semicolon
        text = text.rstrip(';').strip()
        if len(text) <= max_len:
            return text
        return text[:max_len - 3] + '...'


# ═══════════════════════════════════════════════════════════════════════
# DOT Generator
# ═══════════════════════════════════════════════════════════════════════

class DotGenerator:
    """Generate Graphviz DOT from a FlowGraph."""

    COLORS = {
        'start':     ('#67c23a', '#ffffff'),
        'end':       ('#909399', '#ffffff'),
        'process':   ('#4a6fa5', '#ffffff'),
        'decision':  ('#e6a23c', '#ffffff'),
        'loop':      ('#6b4c8a', '#ffffff'),
        'exception': ('#d67236', '#ffffff'),
        'merge':     ('#adb5bd', '#ffffff'),
        'return':    ('#e03131', '#ffffff'),
    }

    SHAPES = {
        'start':     'ellipse',
        'end':       'ellipse',
        'process':   'box',
        'decision':  'diamond',
        'loop':      'hexagon',
        'exception': 'box',
        'merge':     'circle',
        'return':    'box',
    }

    def __init__(self, flow_graph: FlowGraph):
        self.g = flow_graph

    def generate(self) -> str:
        g = self.g
        lines = []
        lines.append(f'// Flowchart: {g.proc_name}')
        lines.append(f'// Nodes: {len(g.nodes)}  Edges: {len(g.edges)}')
        lines.append('')
        lines.append('digraph flowchart {')
        lines.append('  rankdir=LR; fontname="Helvetica,Arial,sans-serif"; fontsize=10;')
        lines.append('  bgcolor="#f8f9fa"; pad=0.4; nodesep=0.25; ranksep=0.4;')
        lines.append('  splines=polyline; newrank=true; compound=true; ordering=out;')
        lines.append('  node [fontname="Helvetica,Arial,sans-serif" fontsize=9 '
                     'shape=box style="filled,rounded" penwidth=1.2 margin="0.12,0.08"];')
        lines.append('  edge [fontname="Helvetica,Arial,sans-serif" fontsize=7 '
                     'color="#5c5c7a" penwidth=0.5 arrowsize=0.6];')
        lines.append('')

        for node in g.nodes:
            self._emit_node(lines, node)

        lines.append('')
        for edge in g.edges:
            self._emit_edge(lines, edge)

        lines.append('}')
        return '\n'.join(lines)

    def _emit_node(self, lines: List[str], node: FlowNode):
        fill, font = self.COLORS.get(node.node_type, ('#4a6fa5', '#ffffff'))
        shape = self.SHAPES.get(node.node_type, 'box')

        # Special: merge nodes are small dots
        if node.node_type == 'merge':
            label = ''
            extra = ' width=0.2 height=0.2 fixedsize=true'
        else:
            label = self._escape(node.label)
            extra = node.extra

        if node.node_type == 'decision':
            extra += ' fontsize=8'

        lines.append(f'  {node.id} [')
        lines.append(f'    label="{label}"')
        lines.append(f'    shape={shape}')
        lines.append(f'    fillcolor="{fill}"')
        lines.append(f'    fontcolor="{font}"')
        if extra:
            lines.append(f'    {extra}')
        lines.append(f'  ];')

    def _emit_edge(self, lines: List[str], edge: FlowEdge):
        label = self._escape(edge.label) if edge.label else ''
        if label:
            lines.append(f'  {edge.from_id} -> {edge.to_id} '
                         f'[label="{label}" fontsize=7];')
        else:
            lines.append(f'  {edge.from_id} -> {edge.to_id};')

    def _escape(self, s: str) -> str:
        return s.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n')


# ═══════════════════════════════════════════════════════════════════════
# Graphviz Renderer
# ═══════════════════════════════════════════════════════════════════════

class GraphvizRenderer:
    """Render DOT files to PNG/SVG/PDF using the 'dot' command."""

    def __init__(self):
        self.dot_path = 'dot'
        # Auto-detect on init
        for path in ['/opt/homebrew/bin/dot', '/usr/local/bin/dot', '/usr/bin/dot']:
            if os.path.exists(path):
                self.dot_path = path
                return

    def is_available(self) -> bool:
        # Check common install locations first
        for path in ['/opt/homebrew/bin/dot', '/usr/local/bin/dot', '/usr/bin/dot']:
            if os.path.exists(path):
                self.dot_path = path
                return True
        # Fallback: try PATH lookup
        try:
            subprocess.run(['dot', '-V'], capture_output=True, timeout=5)
            self.dot_path = 'dot'
            return True
        except Exception:
            return False

    def render(self, dot_source: str, output_path: str, fmt: str = 'png') -> bool:
        try:
            result = subprocess.run(
                [self.dot_path, f'-T{fmt}', '-o', output_path],
                input=dot_source, capture_output=True, text=True, timeout=30
            )
            if result.returncode == 0 and Path(output_path).exists():
                return True
            stderr = result.stderr.strip()
            if stderr:
                print(f"  ⚠ dot warning: {stderr[:120]}", file=sys.stderr)
            return Path(output_path).exists()
        except FileNotFoundError:
            return False
        except Exception as e:
            print(f"  ⚠ Render error: {e}", file=sys.stderr)
            return False


# ═══════════════════════════════════════════════════════════════════════
# CLI
# ═══════════════════════════════════════════════════════════════════════

def main():
    parser = argparse.ArgumentParser(
        description='PL/SQL → Graphviz 图表生成器',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=textwrap.dedent('''\
        示例:
          %(prog)s input.sql                        # 生成总图+分图
          %(prog)s input.sql --mode master          # 只生成总图
          %(prog)s input.sql --mode detail          # 只生成分图
          %(prog)s input.sql --format svg           # SVG 格式
          %(prog)s input.sql -o ./output            # 指定输出目录
        ''')
    )
    parser.add_argument('input', help='PL/SQL 源文件')
    parser.add_argument('--mode', choices=['both', 'master', 'detail'],
                        default='both')
    parser.add_argument('--format', '-f', choices=['png', 'svg', 'pdf'],
                        default='png')
    parser.add_argument('--output-dir', '-o', default=None,
                        help='输出目录 (default: {stem}_graphviz)')
    parser.add_argument('--no-render', action='store_true',
                        help='只生成 DOT，不渲染图片')
    args = parser.parse_args()

    src_path = Path(args.input)
    if not src_path.exists():
        print(f"✗ 文件不存在: {args.input}", file=sys.stderr)
        sys.exit(1)

    source = src_path.read_text(encoding='utf-8', errors='replace')
    stem = src_path.stem

    out_dir = Path(args.output_dir) if args.output_dir else src_path.parent / f"{stem}_graphviz"
    out_dir.mkdir(parents=True, exist_ok=True)

    print(f"📄 解析: {src_path.name} ({len(source)} bytes)")
    print(f"📁 输出: {out_dir}")

    plsql_parser = PlSqlParser(source)
    procedures = plsql_parser.parse()

    if not procedures:
        print("⚠ 未找到任何过程/函数定义")
        return

    print(f"🔍 找到 {len(procedures)} 个过程/函数:")
    for name, proc in procedures.items():
        info = f"   {proc.kind}: {proc.name} (lines {proc.start_line}-{proc.end_line})"
        if proc.calls:
            info += f" → calls: {', '.join(proc.calls)}"
        print(info)

    renderer = GraphvizRenderer()
    can_render = renderer.is_available()
    if not can_render and not args.no_render:
        print("⚠ Graphviz 'dot' 未安装，只生成 DOT 文件")
        print("  安装: brew install graphviz")

    # ── Master call graph ──
    if args.mode in ('both', 'master'):
        print("\n── 生成总图 (call graph) ──")
        builder = CallGraphBuilder(procedures, src_path.name)
        dot_source = builder.build_dot()

        dot_path = out_dir / f"{stem}_master.dot"
        dot_path.write_text(dot_source, encoding='utf-8')
        print(f"  ✓ DOT: {dot_path}")

        if can_render and not args.no_render:
            img_path = out_dir / f"{stem}_master.{args.format}"
            if renderer.render(dot_source, str(img_path), args.format):
                print(f"  ✓ {args.format.upper()}: {img_path}")

    # ── Detail flowcharts ──
    if args.mode in ('both', 'detail'):
        print(f"\n── 生成分图 (flowcharts) × {len(procedures)} ──")
        for name, proc in procedures.items():
            safe_name = re.sub(r'[^A-Za-z0-9_]', '_', name)
            print(f"  → {proc.kind}: {proc.name} ...", end=" ")

            fb = FlowchartBuilder(proc)
            flow_graph = fb.build()

            dg = DotGenerator(flow_graph)
            dot_source = dg.generate()

            dot_path = out_dir / f"{stem}_{safe_name}.dot"
            dot_path.write_text(dot_source, encoding='utf-8')
            print(f"DOT ✓", end="")

            if can_render and not args.no_render:
                img_path = out_dir / f"{stem}_{safe_name}.{args.format}"
                if renderer.render(dot_source, str(img_path), args.format):
                    print(f" {args.format.upper()} ✓", end="")
            print()

    print(f"\n✅ 完成! 输出目录: {out_dir}")


if __name__ == '__main__':
    main()
