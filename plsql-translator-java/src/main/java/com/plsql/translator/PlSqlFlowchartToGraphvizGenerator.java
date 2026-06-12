package com.plsql.translator;

import com.plsql.translator.parser.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.antlr.v4.runtime.misc.Interval;

/**
 * PL/SQL → Graphviz DOT 控制流流程图生成器。
 *
 * 解析 PL/SQL 源码，输出 Graphviz DOT 格式的控制流流程图，
 * 展示实际的执行路径（而非 ANTLR 语法树）。
 */
public class PlSqlFlowchartToGraphvizGenerator {

    static final int NODE_W = 180;
    static final int DECISION_W = 160;
    static final int NODE_H = 36;
    static final int V_GAP = 50;
    static final int BRANCH_H_GAP = 30;
    static final int PAD = 40;
    static final int FONT_SZ = 10;
    static final int FONT_SZ_SM = 9;

    static final String
        C_PROCESS   = "#4a6fa5",
        C_DECISION  = "#e6a23c",
        C_START     = "#67c23a",
        C_END       = "#909399",
        C_LOOP      = "#6b4c8a",
        C_BG        = "#f8f9fa",
        C_EDGE      = "#5c5c7a",
        C_TEXT      = "#ffffff",
        C_EXCEPTION = "#d67236";

    static String currentSource = "";

    enum NodeType { PROCESS, DECISION, START, END, LOOP_BOUNDARY, EXCEPTION, MERGE }

    static class FlowGraph {
        List<FlowNode> nodes = new ArrayList<>();
        List<FlowEdge> edges = new ArrayList<>();
        int nextId = 1;
        String entryId;
        Set<String> exitIds = new LinkedHashSet<>();

        String addNode(NodeType type, String label) {
            String id = "n" + (nextId++);
            nodes.add(new FlowNode(id, type, label));
            return id;
        }

        void addEdge(String fromId, String toId, String label) {
            edges.add(new FlowEdge(fromId, toId, label == null ? "" : label));
        }
    }

    static class FlowResult {
        FlowGraph graph;
        String entryId;
        List<String> exitIds;

        FlowResult(FlowGraph g, String entry, List<String> exits) {
            this.graph = g; this.entryId = entry; this.exitIds = exits;
        }
        FlowResult(FlowGraph g, String entry, String exit) {
            this(g, entry, Collections.singletonList(exit));
        }
    }

    static class FlowNode {
        String id, label;
        NodeType type;
        double w, h;
        FlowNode(String id, NodeType type, String label) {
            this.id = id; this.type = type; this.label = label;
            this.w = (type == NodeType.DECISION || type == NodeType.START || type == NodeType.END) ? DECISION_W : NODE_W;
            this.h = NODE_H;
        }
    }

    static class FlowEdge {
        String fromId, toId, label;
        FlowEdge(String f, String t, String l) { fromId = f; toId = t; label = l; }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ... <input.sql> [output.dot]");
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
        String outPath = args.length >= 2 ? args[1] : args[0].replace(".sql", ".flow.dot");
        String dot = plsqlToFlowchart(src);
        Files.write(Paths.get(outPath), dot.getBytes());
        System.out.println("OK: " + outPath);
    }

    public static String plsqlToFlowchart(String plsql) {
        // ... (same parsing and flow building logic)
        currentSource = plsql;
        CharStream ci = new CaseInsensitiveStream(CharStreams.fromString(plsql));
        PlSqlLexer lex = new PlSqlLexer(ci);
        PlSqlParser par = new PlSqlParser(new CommonTokenStream(lex));
        par.removeErrorListeners();
        ParseTree tree = par.sql_script();

        FlowGraph g = new FlowGraph();
        ParseTree unit = findFirstExecutableUnit(tree);
        if (unit == null) return generateEmptyFallback();

        String entryName = extractEntryName(unit);
        String startId = g.addNode(NodeType.START, truncate(entryName, 19));
        g.entryId = startId;
        g.exitIds.add(startId);

        ParseTree bodyNode = findChildByRule(unit, "body", 0);
        if (bodyNode == null) return generateDot(g);

        ParseTree mainSeq = findChildByRule(bodyNode, "seq_of_statements", 0);
        List<ParseTree> stmts = extractStatementsFromSeq(mainSeq);
        FlowResult bodyResult = processStatementList(g, stmts);

        if (bodyResult != null && bodyResult.entryId != null) {
            g.addEdge(startId, bodyResult.entryId, null);
            g.exitIds = new LinkedHashSet<>(bodyResult.exitIds);

            List<ParseTree> handlers = findAllChildrenByRule(bodyNode, "exception_handler");
            List<String> handlerExits = new ArrayList<>();
            if (!handlers.isEmpty()) {
                String excBorderId = g.addNode(NodeType.EXCEPTION, "EXCEPTION");
                for (String ex : g.exitIds) {
                    g.addEdge(ex, excBorderId, "\u5f02\u5e38");
                }
                for (ParseTree h : handlers) {
                    String excName = extractHandlerName(h);
                    String whenId = g.addNode(NodeType.EXCEPTION, excName);
                    g.addEdge(excBorderId, whenId, null);
                    ParseTree hSeq = findChildByRule(h, "seq_of_statements", 0);
                    List<ParseTree> hStmts = extractStatementsFromSeq(hSeq);
                    FlowResult hResult = processStatementList(g, hStmts);
                    if (hResult != null) {
                        g.addEdge(whenId, hResult.entryId, null);
                        handlerExits.addAll(hResult.exitIds);
                    } else {
                        String np = g.addNode(NodeType.PROCESS, "--");
                        g.addEdge(whenId, np, null); handlerExits.add(np);
                    }
                }
            }

            String endId = g.addNode(NodeType.END, "END " + truncate(entryName, 12));
            for (String ex : g.exitIds) g.addEdge(ex, endId, null);
            g.exitIds.clear();
            g.exitIds.add(endId);
        }

        if (g.nodes.isEmpty()) return generateEmptyFallback();

        // ★★★ 两次图优化 + DOT 生成 ★★★
        optimizeGraph(g);
        return generateDot(g);
    }

    static FlowResult processStatementList(FlowGraph g, List<ParseTree> stmts) {
        if (stmts == null || stmts.isEmpty()) return null;
        String firstEntry = null;
        List<String> prevExits = new ArrayList<>();
        for (ParseTree stmt : stmts) {
            FlowResult r = processOneStatement(g, stmt);
            if (r == null) continue;
            if (firstEntry == null) {
                firstEntry = r.entryId;
            } else {
                for (String pe : prevExits) {
                    String lbl = null;
                    for (FlowNode fn : g.nodes) {
                        if (fn.id.equals(pe) && fn.type == NodeType.DECISION) { lbl = "No"; break; }
                        if (fn.id.equals(pe) && fn.type == NodeType.LOOP_BOUNDARY) { lbl = "Exit"; break; }
                    }
                    g.addEdge(pe, r.entryId, lbl);
                }
            }
            prevExits = new ArrayList<>(r.exitIds);
        }
        if (firstEntry == null) return null;
        return new FlowResult(g, firstEntry, prevExits);
    }

    static FlowResult processOneStatement(FlowGraph g, ParseTree node) {
        if (node == null) return null;
        if (node instanceof TerminalNode) return null;
        int ri = ((RuleContext) node).getRuleIndex();
        if (ri < 0 || ri >= PlSqlParser.ruleNames.length) return null;
        String ruleName = PlSqlParser.ruleNames[ri];
        switch (ruleName) {
            case "statement":
                if (node.getChildCount() > 0) return processOneStatement(g, node.getChild(0));
                return null;
            case "if_statement":
                return processIfStatement(g, node);
            case "loop_statement":
                return processLoopStatement(g, node);
            case "case_statement":
                return processCaseStatement(g, node);
            case "body":
            case "block": {
                ParseTree seq = findChildByRule(node, "seq_of_statements", 0);
                if (seq == null) return null;
                return processStatementList(g, extractStatementsFromSeq(seq));
            }
            default:
                return processBasicStatement(g, node);
        }
    }

    static FlowResult processIfStatement(FlowGraph g, ParseTree node) {
        String cond = extractRuleText(node, "condition");
        String decisionId = g.addNode(NodeType.DECISION, cond.isEmpty() ? "IF" : "IF " + cond);
        List<FlowResult> branchResults = new ArrayList<>();

        ParseTree thenBody = findChildByRule(node, "seq_of_statements", 0);
        if (thenBody != null) {
            FlowResult thenResult = processStatementList(g, extractStatementsFromSeq(thenBody));
            if (thenResult != null) {
                g.addEdge(decisionId, thenResult.entryId, "Yes");
                branchResults.add(thenResult);
            }
        }

        List<ParseTree> elsifParts = findAllChildrenByRule(node, "elsif_part");
        String curDecisionId = decisionId;
        for (ParseTree ep : elsifParts) {
            String elsifCond = extractRuleText(ep, "condition");
            String elsifId = g.addNode(NodeType.DECISION, "ELSIF " + elsifCond);
            g.addEdge(curDecisionId, elsifId, "No");
            curDecisionId = elsifId;
            ParseTree elsifBody = findChildByRule(ep, "seq_of_statements", 0);
            if (elsifBody != null) {
                FlowResult elsifResult = processStatementList(g, extractStatementsFromSeq(elsifBody));
                if (elsifResult != null) {
                    g.addEdge(elsifId, elsifResult.entryId, "Yes");
                    branchResults.add(elsifResult);
                }
            }
        }

        ParseTree elsePart = findChildByRule(node, "else_part", 0);
        if (elsePart != null) {
            ParseTree elseBody = findChildByRule(elsePart, "seq_of_statements", 0);
            if (elseBody != null) {
                FlowResult elseResult = processStatementList(g, extractStatementsFromSeq(elseBody));
                if (elseResult != null) {
                    g.addEdge(curDecisionId, elseResult.entryId, "No");
                    branchResults.add(elseResult);
                }
            }
        }

        List<String> allExits = new ArrayList<>();
        for (FlowResult br : branchResults) allExits.addAll(br.exitIds);
        if (!allExits.isEmpty() && findChildByRule(node, "else_part", 0) == null) {
            allExits.add(decisionId);
        }
        if (allExits.isEmpty()) {
            return new FlowResult(g, decisionId, decisionId);
        }
        return new FlowResult(g, decisionId, allExits);
    }

    static FlowResult processLoopStatement(FlowGraph g, ParseTree node) {
        String loopLabel = extractLoopLabel(node);
        String loopId = g.addNode(NodeType.LOOP_BOUNDARY, loopLabel);

        ParseTree bodySeq = findChildByRule(node, "seq_of_statements", 0);
        FlowResult bodyResult = processStatementList(g, extractStatementsFromSeq(bodySeq));

        if (bodyResult != null && bodyResult.entryId != null) {
            g.addEdge(loopId, bodyResult.entryId, null);
            for (String be : bodyResult.exitIds) g.addEdge(be, loopId, null);
        } else {
            String nopId = g.addNode(NodeType.PROCESS, "--");
            g.addEdge(loopId, nopId, null);
            g.addEdge(nopId, loopId, null);
        }
        return new FlowResult(g, loopId, loopId);
    }

    static FlowResult processCaseStatement(FlowGraph g, ParseTree node) {
        String selector = extractRuleText(node, "expression");
        String switchId = g.addNode(NodeType.DECISION, "CASE " + (selector.isEmpty() ? "" : selector));
        List<ParseTree> whenParts = findAllChildrenByRule(node, "case_when_part");
        List<String> allExits = new ArrayList<>();
        String prevId = null;

        for (ParseTree when : whenParts) {
            String whenCond = extractRuleText(when, "expression");
            String whenId = g.addNode(NodeType.DECISION, "WHEN " + (whenCond.isEmpty() ? "" : whenCond));
            g.addEdge(prevId == null ? switchId : prevId, whenId, "No");
            ParseTree whenThen = findChildByRule(when, "seq_of_statements", 0);
            if (whenThen != null) {
                FlowResult whenResult = processStatementList(g, extractStatementsFromSeq(whenThen));
                if (whenResult != null) {
                    g.addEdge(whenId, whenResult.entryId, "Yes");
                    allExits.addAll(whenResult.exitIds);
                }
            }
            prevId = whenId;
        }

        ParseTree elseClause = findChildByRule(node, "else_clause", 0);
        if (elseClause != null) {
            ParseTree elseSeq = findChildByRule(elseClause, "seq_of_statements", 0);
            if (elseSeq != null) {
                FlowResult elseResult = processStatementList(g, extractStatementsFromSeq(elseSeq));
                if (elseResult != null) {
                    g.addEdge(prevId == null ? switchId : prevId, elseResult.entryId, "No");
                    allExits.addAll(elseResult.exitIds);
                }
            }
        }

        if (allExits.isEmpty()) {
            return new FlowResult(g, switchId, switchId);
        }
        return new FlowResult(g, switchId, allExits);
    }

    static FlowResult processBasicStatement(FlowGraph g, ParseTree node) {
        String text = cleanStatementText(getOriginalText(node));
        if (text.length() > 35) text = text.substring(0, 32) + "...";
        String nid = g.addNode(NodeType.PROCESS, text);
        return new FlowResult(g, nid, nid);
    }

    // ── HELPER: tree traversal utilities ────────────────

    static ParseTree findFirstExecutableUnit(ParseTree node) {
        if (node == null) return null;
        String[] unitRules = {"create_procedure_body", "create_function_body",
                              "create_package", "create_package_body", "create_trigger_body",
                              "procedure_body", "function_body", "package_body",
                              "trigger_body", "anonymous_block"};
        for (String rn : unitRules) {
            ParseTree found = findChildByRule(node, rn, 0);
            if (found != null) return found;
        }
        return null;
    }

    static String extractEntryName(ParseTree node) {
        for (int i = 0; node != null && i < node.getChildCount(); i++) {
            ParseTree c = node.getChild(i);
            if (c instanceof TerminalNode) {
                String t = c.getText().trim();
                if (t.length() > 1 && Character.isLetter(t.charAt(0))
                    && !t.toUpperCase().equals(t)) {
                    return t;
                }
            }
        }
        for (int i = 0; node != null && i < node.getChildCount(); i++) {
            ParseTree c = node.getChild(i);
            String n = extractEntryName(c);
            if (!n.isEmpty()) return n;
        }
        return "unnamed";
    }

    static ParseTree findChildByRule(ParseTree node, String ruleName, int occurrence) {
        if (node == null || ruleName == null) return null;
        int[] count = new int[1];
        return findChildByRuleRecursive(node, ruleName, occurrence, count);
    }

    static ParseTree findChildByRuleRecursive(ParseTree node, String rn, int occ, int[] cnt) {
        if (node instanceof TerminalNode) return null;
        int ri = ((RuleContext)node).getRuleIndex();
        if (ri >= 0 && ri < PlSqlParser.ruleNames.length) {
            String name = PlSqlParser.ruleNames[ri];
            if (name.equals(rn) || name.endsWith(">" + rn) || name.contains(rn)) {
                if (cnt[0] == occ) return node;
                cnt[0]++;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            ParseTree r = findChildByRuleRecursive(node.getChild(i), rn, occ, cnt);
            if (r != null) return r;
        }
        return null;
    }

    static List<ParseTree> findAllChildrenByRule(ParseTree node, String ruleName) {
        List<ParseTree> results = new ArrayList<>();
        if (node == null) return results;
        if (!(node instanceof TerminalNode)) {
            int ri = ((RuleContext)node).getRuleIndex();
            if (ri >= 0 && ri < PlSqlParser.ruleNames.length) {
                if (PlSqlParser.ruleNames[ri].equals(ruleName)
                    || PlSqlParser.ruleNames[ri].endsWith(">" + ruleName)) {
                    results.add(node);
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            results.addAll(findAllChildrenByRule(node.getChild(i), ruleName));
        }
        return results;
    }

    static List<ParseTree> extractStatementsFromSeq(ParseTree seqNode) {
        List<ParseTree> stmts = new ArrayList<>();
        if (seqNode == null) return stmts;
        for (int i = 0; i < seqNode.getChildCount(); i++) {
            ParseTree c = seqNode.getChild(i);
            if (!(c instanceof TerminalNode)) {
                if (PlSqlParser.ruleNames[((RuleContext)c).getRuleIndex()].equals("statement")
                    || PlSqlParser.ruleNames[((RuleContext)c).getRuleIndex()].equals("body")
                    || PlSqlParser.ruleNames[((RuleContext)c).getRuleIndex()].equals("block")) {
                    stmts.add(c);
                }
            }
        }
        return stmts;
    }

    static String extractRuleText(ParseTree node, String ruleName) {
        ParseTree found = findChildByRule(node, ruleName, 0);
        if (found == null) return "";
        return cleanStatementText(getOriginalText(found));
    }

    static String extractHandlerName(ParseTree handler) {
        // Find the terminal that is the exception name (UPPERCASE, not WHEN/THEN)
        for (int i = 0; i < handler.getChildCount(); i++) {
            ParseTree c = handler.getChild(i);
            if (c instanceof TerminalNode) {
                String t = c.getText().trim();
                if (t.length() > 2 && !t.equals("WHEN") && !t.equals("THEN")
                    && t.equals(t.toUpperCase()) && !t.contains("'")) {
                    return truncate(t, 24);
                }
            }
        }
        return "WHEN OTHERS";
    }

    static String extractLoopLabel(ParseTree node) {
        // Detect LOOP type
        String text = getOriginalText(node).trim().toUpperCase();
        if (text.startsWith("FOR")) return truncate("FOR " + extractLoopVariable(node), 22);
        if (text.startsWith("WHILE")) return truncate("WHILE " + extractLoopCond(node), 22);
        for (int i = 0; i < node.getChildCount(); i++) {
            String t = node.getChild(i).getText().trim().toUpperCase();
            if (t.equals("LOOP")) return "LOOP";
            if (t.startsWith("FOR")) return "FOR LOOP";
            if (t.startsWith("WHILE")) return "WHILE LOOP";
        }
        return "LOOP";
    }

    static String extractLoopVariable(ParseTree node) {
        for (int i = 0; i < node.getChildCount(); i++) {
            String t = node.getChild(i).getText().trim();
            if (t.length() > 1 && Character.isLetter(t.charAt(0))
                && !t.toUpperCase().equals(t) && !"LOOP".equals(t.toUpperCase())) {
                return truncate(t, 14);
            }
        }
        return "";
    }

    static String extractLoopCond(ParseTree node) {
        // For WHILE loops, find the condition
        for (int i = 0; i < node.getChildCount(); i++) {
            String t = node.getChild(i).getText().trim();
            if (!t.toUpperCase().equals(t) || t.length() <= 2) continue;
        }
        return "";
    }

    static String getOriginalText(ParseTree node) {
        if (node == null) return "";
        if (currentSource.isEmpty()) return node.getText();
        try {
            if (node instanceof ParserRuleContext) {
                ParserRuleContext ctx = (ParserRuleContext) node;
                Token start = ctx.start;
                Token stop = ctx.stop;
                if (start != null && stop != null) {
                    int a = start.getStartIndex();
                    int b = stop.getStopIndex();
                    if (a >= 0 && b >= a && b < currentSource.length()) {
                        return currentSource.substring(a, b + 1);
                    }
                }
            }
        } catch (Exception ignored) {}
        return node.getText();
    }

    static String cleanStatementText(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        if (s.length() > 40) s = s.substring(0, 37) + "...";
        // Remove trailing semicolons for cleaner labels
        while (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
        return s;
    }

    static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s == null ? "" : s;
        return s.substring(0, max - 1) + "\u2026";
    }

    // ── Fallback ────────────────────────────────────────
    static String generateEmptyFallback() {
        return "digraph PlSqlFlowchart {\n"
             + "  bgcolor=\"#f8f9fa\";\n"
             + "  n0 [label=\"No flow found\", shape=box, style=filled, fillcolor=\"#909399\", fontcolor=white];\n"
             + "}\n";
    }

    // ================================================================
    //  Graph optimization (3 passes) + DOT generation
    // ================================================================

    /**
     * 执行 3 轮图优化，再生成 DOT。
     *
     * 第 1 轮 (Merge): 当多条线汇聚到同一个节点时，插入中继合并节点 (JUNCTION)，
     *   让汇聚点独立出来，而非多条线交叉扎进同一个端口。
     *
     * 第 2 轮 (Branch): 当 IF/CASE 等决策节点分出多条线时，让它们在起始端口
     *   附近先共用一段路径再分叉，使用中继分支节点。
     *
     * 第 3 轮 (Port): 为每条边分配端口方位 (N/S/E/W)，保证每个逻辑块最多
     *   从 4 个方位进出线。
     */
    static void optimizeGraph(FlowGraph g) {
        optimizePass1_merge(g);   // 汇聚合并
        optimizePass2_branch(g);  // 分支整形
        // Pass 3 在 generateDot 中通过 port 方位实现
    }

    // ── Pass 1: 汇聚合并 ──────────────────────────────────
    // 如果某节点有 ≥2 条入边来自不同路径，插入一个 JUNCTION 中继点：
    //   Before:  A──→C   B──→C   →   After:  A──→J──→C   B──→J─┘
    static void optimizePass1_merge(FlowGraph g) {
        // 收集每个节点的入边
        for (FlowNode fn : new ArrayList<>(g.nodes)) {
            // 跳过特殊节点
            if (fn.type == NodeType.START || fn.type == NodeType.END
                || fn.type == NodeType.MERGE || fn.type == NodeType.LOOP_BOUNDARY) {
                continue;
            }
            List<FlowEdge> incoming = new ArrayList<>();
            Set<String> sources = new HashSet<>();
            for (FlowEdge e : g.edges) {
                if (e.toId.equals(fn.id)) {
                    incoming.add(e);
                    sources.add(e.fromId);
                }
            }
            // 需要 ≥2 条不同来源的入边才值得汇聚
            if (incoming.size() < 2 || sources.size() < 2) continue;

            // 创建 JUNCTION 中继节点
            String junctionId = g.addNode(NodeType.MERGE, "");

            // 将入边全部改为指向 junction，保留标签
            for (FlowEdge e : incoming) {
                g.edges.remove(e);
                g.addEdge(e.fromId, junctionId, e.label); // 保留原有标签
            }
            // junction → 原目标
            g.addEdge(junctionId, fn.id, null);
        }
    }

    // ── Pass 2: 分支整形 ──────────────────────────────────
    //  对 DECISION / LOOP_BOUNDARY 等多出边节点，插入中继点
    //  使出线先共走一段再分叉：
    //   Before:  D──(Yes)→A   D──(No)→B
    //   After:   D──→J   J──(Yes)→A   J──(No)→B
    static void optimizePass2_branch(FlowGraph g) {
        for (FlowNode fn : new ArrayList<>(g.nodes)) {
            if (fn.type != NodeType.DECISION
                && fn.type != NodeType.LOOP_BOUNDARY
                && fn.type != NodeType.EXCEPTION) {
                continue;
            }
            // 找到该节点的出边（排除 Exit 标签的特殊边）
            List<FlowEdge> outgoing = new ArrayList<>();
            for (FlowEdge e : g.edges) {
                if (e.fromId.equals(fn.id)) {
                    outgoing.add(e);
                }
            }
            if (outgoing.size() < 2) continue;

            // 创建 JUNCTION 分支节点
            String jId = g.addNode(NodeType.MERGE, "");

            // 原节点 → junction
            g.addEdge(fn.id, jId, null);

            // junction → 各目标（保留原有标签）
            for (FlowEdge e : outgoing) {
                g.edges.remove(e);
                g.addEdge(jId, e.toId, e.label);
            }
        }
    }

    // ================================================================
    //  Graphviz DOT generation
    // ================================================================

    static String generateDot(FlowGraph g) {
        Map<String, Integer> ranks = new HashMap<>();
        if (g.entryId != null) {
            Queue<String> q = new LinkedList<>();
            q.add(g.entryId);
            ranks.put(g.entryId, 0);
            while (!q.isEmpty()) {
                String cur = q.poll();
                int cr = ranks.get(cur);
                for (FlowEdge e : g.edges) {
                    if (e.fromId.equals(cur)) {
                        int extra = "Exit".equals(e.label) ? 2 : 0;
                        int nr = cr + 1 + extra;
                        if (!ranks.containsKey(e.toId) || ranks.get(e.toId) > nr) {
                            ranks.put(e.toId, nr);
                            q.add(e.toId);
                        }
                    }
                }
            }
        }

        // 为每种节点类型定义默认端口方位
        // N=上  S=下  E=右  W=左  (rankdir=TB 方向)
        String tailPortDefault = "s";   // 出线默认朝下
        String headPortDefault = "n";   // 进线默认朝上
        String branchTailPort = "e";    // 分支出线朝右（避免和主线冲突）
        String mergeHeadPort = "n";     // 汇聚节点进线朝上

        StringBuilder sb = new StringBuilder();
        sb.append("digraph PlSqlFlowchart {\n");
        sb.append("  rankdir=TB;\n");
        sb.append("  splines=ortho;\n");           // ★ 正交线，更清晰
        sb.append("  bgcolor=\"").append(C_BG).append("\";\n");
        sb.append("  node [fontsize=").append(FONT_SZ)
          .append(", fontcolor=\"").append(C_TEXT)
          .append("\", margin=0.15];\n");
        sb.append("  edge [color=\"").append(C_EDGE)
          .append("\", penwidth=1];\n\n");

        Map<String, String> idMap = new HashMap<>();

        int cid = 0;
        for (FlowNode fn : g.nodes) {
            idMap.put(fn.id, "n" + (cid++));
        }

        // Identify exception handler and loop body node sets
        Set<String> excNodeIds = new HashSet<>();
        Set<String> loopBodyNodeIds = new HashSet<>();

        for (FlowNode fn : g.nodes) {
            if (fn.type == NodeType.EXCEPTION) {
                Queue<String> q = new LinkedList<>();
                q.add(fn.id);
                excNodeIds.add(fn.id);
                while (!q.isEmpty()) {
                    String cur = q.poll();
                    for (FlowEdge e : g.edges) {
                        if (e.fromId.equals(cur) && !excNodeIds.contains(e.toId)) {
                            excNodeIds.add(e.toId);
                            q.add(e.toId);
                        }
                    }
                }
            }
        }

        for (FlowNode fn : g.nodes) {
            if (fn.type == NodeType.LOOP_BOUNDARY) {
                String bodyEntry = null;
                for (FlowEdge e : g.edges) {
                    if (e.fromId.equals(fn.id) && !"Exit".equals(e.label)) {
                        bodyEntry = e.toId;
                        break;
                    }
                }
                if (bodyEntry != null) {
                    Queue<String> q = new LinkedList<>();
                    q.add(bodyEntry);
                    loopBodyNodeIds.add(bodyEntry);
                    while (!q.isEmpty()) {
                        String cur = q.poll();
                        if (excNodeIds.contains(cur)) continue;
                        for (FlowEdge e : g.edges) {
                            if (e.fromId.equals(cur) && !loopBodyNodeIds.contains(e.toId)
                                && !e.toId.equals(fn.id) && !excNodeIds.contains(e.toId)) {
                                loopBodyNodeIds.add(e.toId);
                                q.add(e.toId);
                            }
                        }
                    }
                }
            }
        }
        for (FlowNode fn : g.nodes) {
            if (fn.type == NodeType.LOOP_BOUNDARY) {
                loopBodyNodeIds.remove(fn.id);
            }
        }

        // ── Exception subgraph ──
        if (!excNodeIds.isEmpty()) {
            sb.append("  subgraph cluster_exceptions {\n");
            sb.append("    label=\"Exception Handlers\";\n");
            sb.append("    style=dashed;\n");
            sb.append("    color=\"#d67236\";\n");
            sb.append("    fontcolor=\"#d67236\";\n");
            for (String eid : excNodeIds) {
                FlowNode fn = findNode(g, eid);
                if (fn == null) continue;
                String vid = idMap.get(fn.id);
                sb.append("    ").append(vid).append(" [");
                appendNodeAttrs(sb, fn);
                sb.append("];\n");
            }
            sb.append("  }\n\n");
        }

        // ── Loop body subgraph ──
        if (!loopBodyNodeIds.isEmpty()) {
            sb.append("  subgraph cluster_loop_body {\n");
            sb.append("    label=\"Loop Body\";\n");
            sb.append("    style=dashed;\n");
            sb.append("    color=\"#6b4c8a\";\n");
            sb.append("    fontcolor=\"#6b4c8a\";\n");
            for (String lid : loopBodyNodeIds) {
                FlowNode fn = findNode(g, lid);
                if (fn == null) continue;
                String vid = idMap.get(fn.id);
                sb.append("    ").append(vid).append(" [");
                appendNodeAttrs(sb, fn);
                sb.append("];\n");
            }
            sb.append("  }\n\n");
        }

        // ── Main nodes ──
        for (FlowNode fn : g.nodes) {
            if (excNodeIds.contains(fn.id) || loopBodyNodeIds.contains(fn.id)) continue;
            String vid = idMap.get(fn.id);
            if (vid == null) continue;
            sb.append("  ").append(vid).append(" [");
            appendNodeAttrs(sb, fn);
            sb.append("];\n");
        }

        sb.append("\n");

        // ── Edges with port assignments (Pass 3) ──
        for (FlowEdge fe : g.edges) {
            String src = idMap.get(fe.fromId);
            String tgt = idMap.get(fe.toId);
            if (src == null || tgt == null) continue;

            // 确定端口方位
            String tailPort = tailPortDefault;
            String headPort = headPortDefault;

            FlowNode srcNode = findNode(g, fe.fromId);
            FlowNode tgtNode = findNode(g, fe.toId);

            if (srcNode != null) {
                // 从 DECISION / LOOP_BOUNDARY 发出的分支线走右侧
                if (srcNode.type == NodeType.DECISION
                    || srcNode.type == NodeType.LOOP_BOUNDARY
                    || srcNode.type == NodeType.EXCEPTION) {
                    tailPort = branchTailPort;
                }
                // JUNCTION (MERGE) 节点：出线默认朝下
                if (srcNode.type == NodeType.MERGE) {
                    tailPort = tailPortDefault;
                }
            }
            if (tgtNode != null) {
                // 到达 JUNCTION (MERGE) 节点：从上方进
                if (tgtNode.type == NodeType.MERGE) {
                    headPort = "n";
                }
                // 回环到 LOOP_BOUNDARY 的线从左侧进
                if (tgtNode.type == NodeType.LOOP_BOUNDARY
                    && srcNode != null && srcNode.type != NodeType.LOOP_BOUNDARY) {
                    headPort = "w";
                }
            }

            sb.append("  ").append(src).append(":").append(tailPort)
              .append(" -> ").append(tgt).append(":").append(headPort);

            if (!fe.label.isEmpty()) {
                sb.append(" [xlabel=\"").append(dotEsc(fe.label)).append("\"");
                sb.append(" fontsize=").append(FONT_SZ_SM);
                sb.append(" fontcolor=\"").append(C_EDGE).append("\"");
                sb.append("]");
            }
            sb.append(";\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    static void appendNodeAttrs(StringBuilder sb, FlowNode fn) {
        switch (fn.type) {
            case START:
                sb.append("shape=ellipse, style=filled, fillcolor=\"").append(C_START).append("\", fontstyle=bold");
                break;
            case END:
                sb.append("shape=ellipse, style=filled, fillcolor=\"").append(C_END).append("\"");
                break;
            case DECISION:
                sb.append("shape=diamond, style=filled, fillcolor=\"").append(C_DECISION).append("\", fontstyle=bold");
                break;
            case LOOP_BOUNDARY:
                sb.append("shape=folder, style=filled, fillcolor=\"").append(C_LOOP).append("\", fontstyle=bold");
                break;
            case EXCEPTION:
                sb.append("shape=parallelogram, style=filled, fillcolor=\"").append(C_EXCEPTION).append("\", fontstyle=bold");
                break;
            case MERGE:
                sb.append("shape=point, width=0.15, height=0.15, fillcolor=\"#adb5bd\"");
                break;
            default:
                sb.append("shape=box, style=\"filled,rounded\", fillcolor=\"").append(C_PROCESS).append("\"");
                break;
        }
        String label = fn.label;
        if (label != null && !label.isEmpty() && fn.type != NodeType.MERGE) {
            sb.append(", label=\"").append(dotEsc(label)).append("\"");
        } else if (fn.type == NodeType.MERGE) {
            sb.append(", label=\"\"");
        }
    }

    static FlowNode findNode(FlowGraph g, String id) {
        for (FlowNode n : g.nodes) if (n.id.equals(id)) return n;
        return null;
    }

    // ── Case-insensitive CharStream wrapper ──
    static class CaseInsensitiveStream implements CharStream {
        private final CharStream stream;
        CaseInsensitiveStream(CharStream s) { this.stream = s; }
        public void consume() { stream.consume(); }
        public int LA(int i) { int c = stream.LA(i); return c >= 'a' && c <= 'z' ? c - 32 : c >= 224 && c <= 255 ? c - 32 : c; }
        public int mark() { return stream.mark(); }
        public void release(int marker) { stream.release(marker); }
        public int index() { return stream.index(); }
        public void seek(int index) { stream.seek(index); }
        public int size() { return stream.size(); }
        public String getSourceName() { return stream.getSourceName(); }
        public String getText(Interval interval) { return stream.getText(interval); }
    }

    static String dotEsc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    static String fmt(double d) {
        return d == (int)d ? String.valueOf((int)d) : String.format("%.1f", d);
    }
}
