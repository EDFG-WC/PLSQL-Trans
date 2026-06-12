package com.plsql.translator;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * PL/SQL → Graphviz DOT 调用关系图生成器。
 *
 * 解析 PL/SQL 源码，提取文件内所有过程/函数及它们之间的调用关系，
 * 输出 Graphviz DOT 格式的调用关系图。
 *
 * 由于 ANTLR PL/SQL 语法中 create_package_body 等规则会被
 * call_statement 抢匹配，本工具使用正则扫描源码提取结构。
 *
 * 用法:
 *   java ... input.sql output.dot
 *   echo 'code' | java ... - output.dot
 */
public class PlSqlCallGraphGenerator {

    static final String C_PROC = "#4a6fa5", C_FUNC = "#6b4c8a",
        C_PKG = "#1e1e2e", C_EXT = "#909399", C_EDGE = "#5c5c7a",
        C_BG = "#f8f9fa";

    static class ProcInfo {
        String name, type, bodyText;
        ProcInfo(String n, String t, String b) { name = n; type = t; bodyText = b; }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: ... <input.sql> [output.dot]");
            System.exit(1);
        }
        String src;
        if ("-".equals(args[0])) {
            BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
            StringBuilder sb = new StringBuilder();
            String line; while ((line = r.readLine()) != null) sb.append(line).append("\n");
            src = sb.toString();
        } else {
            src = new String(Files.readAllBytes(Paths.get(args[0])));
        }
        String out = args.length >= 2 ? args[1] : args[0].replace(".sql", ".call.dot");
        Files.write(Paths.get(out), plsqlToCallGraph(src).getBytes());
        System.out.println("✓ " + out);
    }

    public static String plsqlToCallGraph(String source) {
        String clean = source.replaceAll("/\\*.*?\\*/", " ").replaceAll("--[^\n]*", "\n");

        List<ProcInfo> procs = new ArrayList<>();

        // 策略：用文本块扫描找到所有顶层声明，然后提取内部过程/函数
        List<Block> blocks = findTopBlocks(clean);
        for (Block b : blocks) extract(clean, b, procs);

        if (procs.isEmpty()) {
            return "digraph PlSqlCallGraph {\n  bgcolor=\"" + C_BG
                 + "\";\n  n0 [label=\"No procedures/functions found\""
                 + ", shape=box, style=filled, fillcolor=\"" + C_EXT
                 + "\", fontcolor=white];\n}\n";
        }

        // 去重
        Map<String, ProcInfo> dedup = new LinkedHashMap<>();
        for (ProcInfo p : procs) {
            if (!dedup.containsKey(p.name.toLowerCase())) dedup.put(p.name.toLowerCase(), p);
        }
        procs = new ArrayList<>(dedup.values());

        // 识别包名（不参与内部调用检测）
        String pkgName = "";
        for (ProcInfo p : procs) {
            if (p.type.equals("PACKAGE")) { pkgName = p.name.toLowerCase(); break; }
        }
        final String pkgLower = pkgName;

        // 本地名字集合：只包含 PROCEDURE/FUNCTION，包含全名和短名（去包前缀）
        Set<String> localFullNames = new HashSet<>();
        Set<String> localShortNames = new HashSet<>();
        for (ProcInfo p : procs) {
            if (p.type.equals("PACKAGE")) continue;
            String key = p.name.toLowerCase();
            localFullNames.add(key);
            if (key.contains(".")) {
                localShortNames.add(key.substring(key.lastIndexOf('.') + 1));
            }
        }

        // 检测调用
        Map<String, Set<String>> calls = new LinkedHashMap<>();
        Map<String, Set<String>> extCalls = new LinkedHashMap<>();
        for (ProcInfo p : procs) {
            calls.put(p.name.toLowerCase(), new LinkedHashSet<>());
            extCalls.put(p.name.toLowerCase(), new LinkedHashSet<>());
            if (p.type.equals("PACKAGE")) continue; // 包节点不扫描调用
            Matcher m = Pattern.compile("(?<![a-zA-Z0-9_])([a-zA-Z]\\w*)\\s*\\(").matcher(p.bodyText);
            Set<String> seenExt = new HashSet<>();
            while (m.find()) {
                String callee = m.group(1).toLowerCase();
                // 跳过自身、包名、SQL关键字
                if (callee.equals(p.name.toLowerCase()) || callee.equals(pkgLower)
                    || KW.contains(callee)) continue;

                // 检查是否是本地调用（全名或短名匹配）
                if (localFullNames.contains(callee)) {
                    calls.get(p.name.toLowerCase()).add(callee);
                } else if (callee.contains(".")) {
                    // 带包前缀的全名匹配
                    String shortPart = callee.substring(callee.lastIndexOf('.') + 1);
                    if (localShortNames.contains(shortPart)) {
                        // 找到对应的全名
                        for (String full : localFullNames) {
                            if (full.endsWith("." + shortPart)) {
                                calls.get(p.name.toLowerCase()).add(full);
                                break;
                            }
                        }
                    }
                } else if (localShortNames.contains(callee)) {
                    // 短名匹配：找到对应的全名
                    for (String full : localFullNames) {
                        if (full.endsWith("." + callee)) {
                            calls.get(p.name.toLowerCase()).add(full);
                            break;
                        }
                    }
                } else if (seenExt.add(callee) && extCalls.get(p.name.toLowerCase()).size() < 6) {
                    // 外部调用：只保留 dbms_/utl_/plsql 风格或全大写的
                    if (callee.startsWith("dbms_") || callee.startsWith("utl_")
                        || isAllUpper(callee)) {
                        extCalls.get(p.name.toLowerCase()).add(callee);
                    }
                }
            }
        }

        // 生成 DOT
        StringBuilder sb = new StringBuilder();
        sb.append("digraph PlSqlCallGraph {\n  rankdir=LR;\n  bgcolor=\"")
          .append(C_BG).append("\";\n  node [fontsize=10, margin=0.15];\n")
          .append("  edge [color=\"").append(C_EDGE).append("\", penwidth=1.5];\n\n");

        for (ProcInfo p : procs) {
            String fc = p.type.equals("FUNCTION") ? C_FUNC : p.type.equals("PACKAGE") ? C_PKG : C_PROC;
            String shape = p.type.equals("PACKAGE") ? "folder" : "box";
            sb.append("  ").append(safeId(p.name))
              .append(" [label=\"").append(esc(shorten(p.name, 28)))
              .append("\\n(").append(p.type).append(")\"")
              .append(", shape=").append(shape)
              .append(", style=\"filled,rounded\", fillcolor=\"").append(fc)
              .append("\", fontcolor=\"#ffffff\"];\n");
        }

        Set<String> allExt = new LinkedHashSet<>();
        for (Set<String> s : extCalls.values()) allExt.addAll(s);
        if (!allExt.isEmpty()) {
            sb.append("\n  // ── 外部调用 ──\n");
            for (String e : allExt)
                sb.append("  ").append(safeId(e))
                  .append(" [label=\"").append(esc(shorten(e, 24)))
                  .append("\\n(external)\", shape=box, style=\"filled,dashed\", fillcolor=\"")
                  .append(C_EXT).append("\", fontcolor=\"#ffffff\", fontsize=9];\n");
        }

        sb.append("\n");
        for (ProcInfo p : procs) {
            String f = safeId(p.name);
            for (String t : calls.get(p.name.toLowerCase()))
                if (localFullNames.contains(t))
                    sb.append("  ").append(f).append(" -> ").append(safeId(t)).append(";\n");
            for (String t : extCalls.get(p.name.toLowerCase()))
                sb.append("  ").append(f).append(" -> ").append(safeId(t))
                  .append(" [style=dashed, arrowhead=open, color=\"").append(C_EXT).append("\"];\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    // ── 块扫描 ──────────────────────────────────────────
    static class Block { int start, end; String type, name;
        Block(int s, int e, String t, String n) { start=s; end=e; type=t; name=n; } }

    static List<Block> findTopBlocks(String src) {
        List<Block> blocks = new ArrayList<>();
        // 先找包体（最长匹配优先）
        Matcher m = Pattern.compile(
            "(?i)create\\s+(or\\s+replace\\s+)?package\\s+body\\s+(\\w+)"
        ).matcher(src);
        while (m.find()) {
            int s = m.start();
            int e = findEnd(src, s);
            if (e > s) blocks.add(new Block(s, e, "PACKAGE_BODY", m.group(2)));
        }
        // 再找独立过程
        m = Pattern.compile(
            "(?i)create\\s+(or\\s+replace\\s+)?procedure\\s+(\\w+)"
        ).matcher(src);
        while (m.find()) {
            int s = m.start();
            int e = findEnd(src, s);
            // 跳过已被包体覆盖的
            if (e > s && !insideAnyBlock(s, blocks))
                blocks.add(new Block(s, e, "PROCEDURE", m.group(2)));
        }
        // 再找独立函数
        m = Pattern.compile(
            "(?i)create\\s+(or\\s+replace\\s+)?function\\s+(\\w+)"
        ).matcher(src);
        while (m.find()) {
            int s = m.start();
            int e = findEnd(src, s);
            if (e > s && !insideAnyBlock(s, blocks))
                blocks.add(new Block(s, e, "FUNCTION", m.group(2)));
        }
        blocks.sort(Comparator.comparingInt(b -> b.start));
        return blocks;
    }

    static boolean insideAnyBlock(int pos, List<Block> blocks) {
        for (Block b : blocks) if (pos >= b.start && pos < b.end) return true;
        return false;
    }

    static int findEnd(String src, int start) {
        String s = src.substring(start);
        // 找最后一个 END\s*<name>?\s*;?\s*/?
        Matcher em = Pattern.compile("\\bend\\b", Pattern.CASE_INSENSITIVE).matcher(s);
        int lastEnd = -1;
        while (em.find()) lastEnd = em.start();
        if (lastEnd < 0) return start + s.length();
        int after = lastEnd + 3;
        while (after < s.length() && " \n\r\t".indexOf(s.charAt(after)) >= 0) after++;
        while (after < s.length() && (Character.isLetter(s.charAt(after))
               || s.charAt(after) == '_' || s.charAt(after) == '.')) after++;
        while (after < s.length() && " \n\r\t".indexOf(s.charAt(after)) >= 0) after++;
        if (after < s.length() && s.charAt(after) == ';') after++;
        while (after < s.length() && " \n\r\t".indexOf(s.charAt(after)) >= 0) after++;
        if (after < s.length() && s.charAt(after) == '/') after++;
        return start + after;
    }

    // ── 提取过程/函数 ────────────────────────────────────
    static void extract(String fullSrc, Block block, List<ProcInfo> result) {
        String text = fullSrc.substring(block.start, block.end);

        if (block.type.equals("PACKAGE_BODY")) {
            result.add(new ProcInfo(block.name, "PACKAGE", text));
            // 找 body 内的 procedure/function 定义
            int isPos = findIs(text);
            if (isPos < 0) return;
            String bodyPart = text.substring(isPos);
            Matcher m = Pattern.compile(
                "(?im)^\\s*(procedure|function)\\s+(\\w+)"
            ).matcher(bodyPart);
            while (m.find()) {
                String t = m.group(1).toUpperCase();
                String n = m.group(2);
                int ps = m.start();
                int pe = findProcSectionEnd(bodyPart, ps);
                String pb = pe > ps ? bodyPart.substring(ps, pe) : bodyPart.substring(ps);
                result.add(new ProcInfo(block.name + "." + n, t.equals("FUNCTION") ? "FUNCTION" : "PROCEDURE", pb));
            }
        } else {
            result.add(new ProcInfo(block.name, block.type, text));
        }
    }

    static int findIs(String text) {
        Matcher m = Pattern.compile("(?i)(?<![a-z])is\\s|\\bas\\s").matcher(text);
        return m.find() ? m.end() : -1;
    }

    static int findProcSectionEnd(String text, int start) {
        String s = text.substring(start);
        // 找下一个 procedure/function 或最远的 END
        // 只在行首匹配（排除正文中的误匹配）
        Matcher nm = Pattern.compile(
            "(?im)^\\s*(procedure|function)\\s+\\w+"
        ).matcher(s);
        // 跳过第一个匹配（就是它自己）
        if (nm.find()) {
            // 继续找下一个
            if (nm.find()) return start + nm.start();
        }
        Matcher em = Pattern.compile("\\bend\\b", Pattern.CASE_INSENSITIVE).matcher(s);
        int lastEnd = -1;
        while (em.find()) lastEnd = em.start();
        if (lastEnd > 0) return start + lastEnd + 3;
        return start + s.length();
    }

    // ── 工具 ─────────────────────────────────────────────
    static String safeId(String n) { return "n" + n.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase(); }
    static String esc(String s) {
        return s == null ? "" : s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n");
    }
    static String shorten(String s, int m) {
        return s == null || s.length() <= m ? (s == null ? "" : s) : s.substring(0, m-1) + "\u2026";
    }

    static final Set<String> KW = new HashSet<>(Arrays.asList(
        "select","from","where","and","or","not","in","is","null",
        "insert","update","delete","into","values","set","as","on",
        "join","left","right","inner","outer","full","cross",
        "group","by","having","order","asc","desc","limit","offset",
        "case","when","then","else","end","begin","declare",
        "if","else","elsif","loop","for","while","exit","return",
        "procedure","function","package","trigger","body","type",
        "create","replace","alter","drop","table","view","index",
        "cursor","open","fetch","close","commit","rollback",
        "exception","raise","pragma","execute","immediate",
        "number","varchar2","date","char","clob","blob","integer",
        "raise_application_error","dbms_output","dbms_lob","utl_file",
        "utl_raw","utl_encode","chr","length","substr","instr",
        "replace","translate","trim","ltrim","rtrim","upper","lower",
        "nvl","coalesce","nullif","to_char","to_number","to_date",
        "sysdate","systimestamp","count","sum","avg","min","max",
        "rowtype","ref","cursor","sys_refcursor",
        "decode","empty_clob","empty_blob","raw","ceil","mod",
        "divisible","power","abs","sign","round","trunc"
    ));

    static boolean isAllUpper(String s) {
        if (s == null || s.length() <= 1) return false;
        boolean hasLetter = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'a' && c <= 'z') return false;
            if (c >= 'A' && c <= 'Z') hasLetter = true;
        }
        return hasLetter;
    }
}
