package com.plsql.translator;

import com.plsql.translator.parser.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * PL/SQL → Draw.io 图生成器。
 *
 * 解析 PL/SQL 源码，直接输出 drawio 格式的 XML 图文件。
 * 零依赖纯字符串拼接，不依赖任何第三方库。
 *
 * 用法:
 *   java ... input.sql output.drawio
 *   echo 'code' | java ... - output.drawio
 */
public class PlSqlDrawioGenerator {

    // ── 布局参数 ─────────────────────────────────────────
    static final int NODE_W = 160;
    static final int NODE_H = 34;
    static final int H_GAP = 6;
    static final int V_GAP = 40;
    static final int PAD = 30;

    // ── 颜色 ─────────────────────────────────────────────
    static final String
        C_ROOT    = "#1e1e2e",
        C_RULE    = "#2d2d44",
        C_KEYWORD = "#c92a2a",
        C_TOKEN   = "#364fc7",
        C_STRING  = "#2b8a3e",
        C_NUMBER  = "#e67700",
        C_EDGE    = "#5c5c7a",
        C_BG      = "#f8f9fa";

    // ── 树节点 ───────────────────────────────────────────
    static class LNode {
        String id, label, ruleName, tokenText;
        boolean isRule;
        double cx, y, width;
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
            System.err.println("用法: ... input.sql [output.drawio]");
            System.err.println("       echo 'code' | ... - [output.drawio]");
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

        String outPath = args.length >= 2 ? args[1] : args[0].replace(".sql", ".drawio");

        String xml = plsqlToDrawio(src);
        Files.write(Paths.get(outPath), xml.getBytes());
        System.out.println("✓ " + outPath);
    }

    /** 主方法：PL/SQL 源码 → drawio XML */
    public static String plsqlToDrawio(String plsql) {
        CharStream ci = CharStreams.fromString(plsql);
        PlSqlLexer lex = new PlSqlLexer(ci);
        PlSqlParser par = new PlSqlParser(new CommonTokenStream(lex));
        par.removeErrorListeners();
        ParseTree tree = par.sql_script();
        return generateDrawio(tree, par);
    }

    public static String generateDrawio(ParseTree tree, PlSqlParser parser) {
        // 1) 构建树
        LNode root = new LNode(tree, parser, 0);
        // 2) 计算布局
        layout(root, PAD, 0);
        // 3) 收集节点
        List<LNode> all = new ArrayList<>();
        collect(root, all);

        // 4) 画布尺寸
        double mx = PAD, my = PAD;
        for (LNode n : all) {
            mx = Math.max(mx, n.cx + n.width / 2);
            my = Math.max(my, n.y + NODE_H);
        }
        mx += PAD; my += PAD;

        // 5) 生成 XML
        StringBuilder sb = new StringBuilder();
        int cid = 2;
        Map<String, String> ids = new HashMap<>();

        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<mxfile host=\"AST\" modified=\"2026\" agent=\"plsql\" type=\"device\">\n");
        sb.append("  <diagram name=\"PL/SQL AST\" id=\"d1\">\n");
        sb.append("    <mxGraphModel pageWidth=\"").append(fmt(mx)).append("\"")
          .append(" pageHeight=\"").append(fmt(my)).append("\"")
          .append(" background=\"").append(C_BG).append("\"")
          .append(" grid=\"1\" gridSize=\"10\">\n");
        sb.append("      <root>\n");
        sb.append("        <mxCell id=\"0\"/>\n");
        sb.append("        <mxCell id=\"1\" parent=\"0\"/>\n");

        for (LNode n : all) {
            String id = String.valueOf(cid++);
            ids.put(n.id, id);
            sb.append("        <mxCell id=\"").append(id).append("\"")
              .append(" value=\"").append(xmlEsc(n.label)).append("\"")
              .append(" vertex=\"1\" parent=\"1\"")
              .append(" style=\"").append(styleOf(n)).append("\">\n");
            sb.append("          <mxGeometry")
              .append(" x=\"").append(fmt(n.cx - n.width / 2)).append("\"")
              .append(" y=\"").append(fmt(n.y)).append("\"")
              .append(" width=\"").append(fmt(Math.min(n.width, 180.0))).append("\"")
              .append(" height=\"").append(NODE_H).append("\"")
              .append(" as=\"geometry\"/>\n");
            sb.append("        </mxCell>\n");
        }

        for (LNode n : all) {
            String pid = ids.get(n.id);
            for (LNode ch : n.children) {
                String cid2 = ids.get(ch.id);
                if (cid2 == null) continue;
                String eid = String.valueOf(cid++);
                sb.append("        <mxCell id=\"").append(eid).append("\"")
                  .append(" edge=\"1\" parent=\"1\"")
                  .append(" source=\"").append(pid).append("\"")
                  .append(" target=\"").append(cid2).append("\"")
                  .append(" style=\"edgeStyle=orthogonalEdgeStyle;strokeColor=")
                  .append(C_EDGE).append(";strokeWidth=0.5;rounded=1;\">\n");
                sb.append("          <mxGeometry relative=\"1\" as=\"geometry\"/>\n");
                sb.append("        </mxCell>\n");
            }
        }

        sb.append("      </root>\n");
        sb.append("    </mxGraphModel>\n");
        sb.append("  </diagram>\n");
        sb.append("</mxfile>\n");
        return sb.toString();
    }

    // ── 树布局 ────────────────────────────────────────────
    static double layout(LNode n, double x, int d) {
        n.depth = d;
        if (n.children.isEmpty()) {
            n.width = NODE_W;
            n.cx = x + n.width / 2;
            n.y = d * (NODE_H + V_GAP);
            return n.width;
        }
        double cx = x;
        for (LNode ch : n.children) cx += layout(ch, cx, d + 1);
        double tw = Math.max(NODE_W, cx - x);
        n.cx = x + tw / 2;
        n.y = d * (NODE_H + V_GAP);
        n.width = tw;
        return tw;
    }

    static void collect(LNode n, List<LNode> ls) {
        ls.add(n);
        for (LNode ch : n.children) collect(ch, ls);
    }

    // ── 样式 ──────────────────────────────────────────────
    static String styleOf(LNode n) {
        String fc, fs = "";
        if (n.isRule) {
            if (n.depth == 0) { fc = C_ROOT; } else {
                String rn = n.ruleName != null ? n.ruleName : "";
                if (rn.contains("procedure") || rn.contains("function")
                    || rn.contains("package") || rn.contains("trigger")
                    || rn.contains("type") || rn.contains("body"))
                    fc = C_STRING;
                else fc = C_RULE;
            }
        } else {
            String t = n.tokenText != null ? n.tokenText : "";
            if (t.isEmpty()) fc = C_TOKEN;
            else if (t.chars().allMatch(Character::isUpperCase) && t.length() > 1
                     && !t.contains("_") && !t.contains(" "))
                { fc = C_KEYWORD; fs = ";fontStyle=4"; }
            else if (t.startsWith("'") || t.startsWith("\""))
                { fc = C_STRING; fs = ";fontStyle=3"; }
            else if (t.chars().allMatch(Character::isDigit))
                { fc = C_NUMBER; }
            else
                { fc = C_TOKEN; fs = ";fontStyle=3"; }
        }
        return "rounded=" + (n.isRule ? "1" : "0")
            + ";whiteSpace=wrap;html=1;fontSize=10;fontColor=#ffffff"
            + ";fillColor=" + fc + ";strokeColor=none;overflow=hidden"
            + fs;
    }

    static String fmt(double d) {
        return d == (int)d ? String.valueOf((int)d) : String.format("%.1f", d);
    }

    static String xmlEsc(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;")
                .replace(">","&gt;").replace("\"","&quot;")
                .replace("'","&apos;");
    }

    static String shorten(String s, int m) {
        if (s == null || s.length() <= m) return s == null ? "" : s;
        return s.substring(0, m - 1) + "…";
    }
}
