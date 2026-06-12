package com.plsql.translator;

import com.plsql.translator.parser.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * PL/SQL → Graphviz DOT 语法树生成器。
 *
 * 解析 PL/SQL 源码，输出 Graphviz DOT 格式的语法树图。
 * 零依赖纯字符串拼接。
 *
 * 用法:
 *   java ... input.sql output.dot
 *   echo 'code' | java ... - output.dot
 */
public class PlSqlGraphvizGenerator {

    // ── 颜色 ─────────────────────────────────────────────
    static final String
        C_ROOT    = "#1e1e2e",
        C_RULE    = "#2d2d44",
        C_KEYWORD = "#c92a2a",
        C_TOKEN   = "#364fc7",
        C_STRING  = "#2b8a3e",
        C_NUMBER  = "#e67700",
        C_EDGE    = "#5c5c7a";

    // ── 树节点 ───────────────────────────────────────────
    static class LNode {
        String id, label, ruleName, tokenText;
        boolean isRule;
        int depth;
        List<LNode> children = new ArrayList<>();

        LNode(ParseTree node, PlSqlParser parser, int depth) {
            this.depth = depth;
            this.id = "n" + System.identityHashCode(node);
            if (node instanceof TerminalNode) {
                this.isRule = false;
                this.tokenText = node.getText();
                this.label = shorten(tokenText, 28);
            } else {
                this.isRule = true;
                String fn = parser.ruleNames[((RuleContext)node).getRuleIndex()];
                this.ruleName = fn;
                String sn = fn.contains(">") ? fn.substring(fn.lastIndexOf('>') + 1) : fn;
                this.label = shorten(sn.replace('_', ' '), 28);
                for (int i = 0; i < node.getChildCount(); i++) {
                    ParseTree c = node.getChild(i);
                    if (c instanceof TerminalNode && c.getText().trim().isEmpty()) continue;
                    LNode ch = new LNode(c, parser, depth + 1);
                    if (ch.label != null && !ch.label.isEmpty()) children.add(ch);
                }
            }
        }
    }

    // ── 入口 ─────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: ... input.sql [output.dot]");
            System.err.println("       echo 'code' | ... - [output.dot]");
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

        String outPath = args.length >= 2 ? args[1] : args[0].replace(".sql", ".dot");

        String dot = plsqlToGraphviz(src);
        Files.write(Paths.get(outPath), dot.getBytes());
        System.out.println("✓ " + outPath);
    }

    /** 主方法：PL/SQL 源码 → Graphviz DOT */
    public static String plsqlToGraphviz(String plsql) {
        CharStream ci = CharStreams.fromString(plsql);
        PlSqlLexer lex = new PlSqlLexer(ci);
        PlSqlParser par = new PlSqlParser(new CommonTokenStream(lex));
        par.removeErrorListeners();
        ParseTree tree = par.sql_script();
        return generateDot(tree, par);
    }

    public static String generateDot(ParseTree tree, PlSqlParser parser) {
        // 1) 构建树
        LNode root = new LNode(tree, parser, 0);

        // 2) 收集节点和边
        List<LNode> all = new ArrayList<>();
        collect(root, all);

        // 3) 生成 DOT
        StringBuilder sb = new StringBuilder();
        sb.append("digraph PlSqlAST {\n");
        sb.append("  rankdir=TB;\n");
        sb.append("  bgcolor=\"#f8f9fa\";\n");
        sb.append("  node [style=\"filled,rounded\", fontsize=10, fontcolor=\"#ffffff\", margin=0.2];\n");
        sb.append("  edge [color=\"").append(C_EDGE).append("\", penwidth=0.7];\n\n");

        int cid = 0;
        Map<String, String> ids = new HashMap<>();

        for (LNode n : all) {
            String id = "n" + (cid++);
            ids.put(n.id, id);
            String dotAttr;
            if (n.isRule) {
                String fc = (n.depth == 0) ? C_ROOT : C_RULE;
                String rn = n.ruleName != null ? n.ruleName : "";
                if (rn.contains("procedure") || rn.contains("function")
                    || rn.contains("package") || rn.contains("trigger")
                    || rn.contains("type") || rn.contains("body")) {
                    fc = C_STRING;
                }
                dotAttr = "shape=box, style=\"filled,rounded\", fillcolor=\"" + fc + "\"";
            } else {
                String fc = C_TOKEN;
                String extra = "";
                String t = n.tokenText != null ? n.tokenText : "";
                if (t.isEmpty()) {
                    fc = C_TOKEN;
                } else if (t.chars().allMatch(Character::isUpperCase) && t.length() > 1
                           && !t.contains("_") && !t.contains(" ")) {
                    fc = C_KEYWORD;
                    extra = ", fontstyle=bold";
                } else if (t.startsWith("'") || t.startsWith("\"")) {
                    fc = C_STRING;
                    extra = ", fontstyle=italic";
                } else if (t.chars().allMatch(Character::isDigit)) {
                    fc = C_NUMBER;
                } else {
                    fc = C_TOKEN;
                    extra = ", fontstyle=italic";
                }
                dotAttr = "shape=box, style=\"filled\", fillcolor=\"" + fc + "\"" + extra;
            }
            sb.append("  ").append(id).append(" [")
              .append("label=\"").append(dotEsc(n.label)).append("\", ")
              .append(dotAttr)
              .append("];\n");
        }

        sb.append("\n");

        for (LNode n : all) {
            String pid = ids.get(n.id);
            if (pid == null) continue;
            for (LNode ch : n.children) {
                String cid2 = ids.get(ch.id);
                if (cid2 == null) continue;
                sb.append("  ").append(pid).append(" -> ").append(cid2).append(";\n");
            }
        }

        sb.append("}\n");
        return sb.toString();
    }

    static void collect(LNode n, List<LNode> ls) {
        ls.add(n);
        for (LNode ch : n.children) collect(ch, ls);
    }

    static String fmt(double d) {
        return d == (int)d ? String.valueOf((int)d) : String.format("%.1f", d);
    }

    static String dotEsc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    static String shorten(String s, int m) {
        if (s == null || s.length() <= m) return s == null ? "" : s;
        return s.substring(0, m - 1) + "\u2026";
    }
}
