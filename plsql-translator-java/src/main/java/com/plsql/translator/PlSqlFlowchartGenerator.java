package com.plsql.translator;

import com.plsql.translator.parser.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.antlr.v4.runtime.misc.Interval;

/**
 * PL/SQL to method flowchart generator.
 * Converts PL/SQL code into a control-flow diagram (flowchart) in drawio format,
 * showing the actual execution flow rather than the ANTLR parse tree.
 */
public class PlSqlFlowchartGenerator {

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
            System.err.println("Usage: ... <input.sql> [output.drawio]");
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
        String outPath = args.length >= 2 ? args[1] : args[0].replace(".sql", ".flow.drawio");
        String xml = plsqlToFlowchart(src);
        Files.write(Paths.get(outPath), xml.getBytes());
        System.out.println("OK: " + outPath);
    }

    public static String plsqlToFlowchart(String plsql) {
        // Normalize to uppercase for case-insensitive parsing (ANTLR 4.9.3 doesn't support caseInsensitive option)
        currentSource = plsql; // Store original for label text extraction
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
        if (bodyNode == null) return generateDrawio(g);

        ParseTree mainSeq = findChildByRule(bodyNode, "seq_of_statements", 0);
        List<ParseTree> stmts = extractStatementsFromSeq(mainSeq);
        FlowResult bodyResult = processStatementList(g, stmts);

        if (bodyResult != null && bodyResult.entryId != null) {
            g.addEdge(startId, bodyResult.entryId, null);
            g.exitIds = new LinkedHashSet<>(bodyResult.exitIds);

            List<ParseTree> handlers = findAllChildrenByRule(bodyNode, "exception_handler");
            List<String> handlerExits = new ArrayList<>();
            if (!handlers.isEmpty()) {
                // 异常处理作为侧边分支：从主流程最后一个节点引出"[异常]"边
                String excBorderId = g.addNode(NodeType.EXCEPTION, "EXCEPTION");
                for (String ex : g.exitIds) {
                    g.addEdge(ex, excBorderId, "\u5f02\u5e38");  // "异常"
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
                // 主流程正常往下走，异常分支在右侧
            }

            String endId = g.addNode(NodeType.END, "END " + truncate(entryName, 12));
            for (String ex : g.exitIds) g.addEdge(ex, endId, null);
            g.exitIds.clear();
            g.exitIds.add(endId);
        }

        if (g.nodes.isEmpty()) return generateEmptyFallback();
        return generateDrawio(g);
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
        // If no ELSE, add decision for No-path
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
        if (text.isEmpty()) return null;
        String id = g.addNode(NodeType.PROCESS, truncate(text, 19));
        return new FlowResult(g, id, id);
    }

    // ================================================================
    //  Helpers
    // ================================================================

    static String cleanStatementText(String s) {
        if (s == null) return "";
        // First try: normalize existing whitespace
        s = s.trim().replaceAll("\\s+", " ");
        // Fallback: add spaces between tokens that got concatenated
        // Insert space between word and non-word boundaries (preserve existing spaces)
        s = s.replaceAll("([a-zA-Z0-9_#])([^a-zA-Z0-9_#\\s'])", "$1 $2");
        s = s.replaceAll("([^a-zA-Z0-9_#\\s'])([a-zA-Z0-9_#])", "$1 $2");
        // Clean up multiple spaces
        s = s.replaceAll("\\s+", " ").trim();
        if (s.startsWith(";") || s.startsWith(",")) s = s.substring(1).trim();
        if (s.startsWith("'") || s.startsWith("\"")) s = s.substring(1);
        return s;
    }

    static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s == null ? "" : s;
        return s.substring(0, max - 2) + "...";
    }

    static String extractRuleText(ParseTree node, String ruleName) {
        ParseTree child = findChildByRule(node, ruleName, 0);
        if (child == null) return "";
        return truncate(getOriginalText(child).trim().replaceAll("\\s+", " "), 22);
    }

    static ParseTree findChildByRule(ParseTree node, String ruleName, int occurrence) {
        if (node == null) return null;
        int found = 0;
        // Search direct children and recurse into unnamed subcontexts
        java.util.Stack<ParseTree> stack = new java.util.Stack<>();
        stack.push(node);
        while (!stack.isEmpty()) {
            ParseTree cur = stack.pop();
            for (int i = 0; i < cur.getChildCount(); i++) {
                ParseTree c = cur.getChild(i);
                if (!(c instanceof RuleContext)) continue;
                int ri = ((RuleContext) c).getRuleIndex();
                if (ri >= 0 && ri < PlSqlParser.ruleNames.length) {
                    if (PlSqlParser.ruleNames[ri].equals(ruleName)) {
                        if (found == occurrence) return c;
                        found++;
                    }
                    // Don't recurse into named rules (they have their own internal structure)
                    // Only recurse through unnamed rules (with different depths... we skip those)
                } else {
                    // Unnamed subcontext (ri < 0) - recurse into it
                    stack.push(c);
                }
            }
        }
        return null;
    }

    static List<ParseTree> findAllChildrenByRule(ParseTree node, String ruleName) {
        List<ParseTree> result = new ArrayList<>();
        if (node == null) return result;
        java.util.Stack<ParseTree> stack = new java.util.Stack<>();
        stack.push(node);
        while (!stack.isEmpty()) {
            ParseTree cur = stack.pop();
            for (int i = 0; i < cur.getChildCount(); i++) {
                ParseTree c = cur.getChild(i);
                if (!(c instanceof RuleContext)) continue;
                int ri = ((RuleContext) c).getRuleIndex();
                if (ri >= 0 && ri < PlSqlParser.ruleNames.length) {
                    if (PlSqlParser.ruleNames[ri].equals(ruleName)) {
                        result.add(c);
                    }
                } else {
                    stack.push(c);
                }
            }
        }
        return result;
    }

    static List<ParseTree> extractStatementsFromSeq(ParseTree seqNode) {
        List<ParseTree> result = new ArrayList<>();
        if (seqNode == null) return result;
        java.util.Stack<ParseTree> stack = new java.util.Stack<>();
        stack.push(seqNode);
        while (!stack.isEmpty()) {
            ParseTree cur = stack.pop();
            for (int i = 0; i < cur.getChildCount(); i++) {
                ParseTree c = cur.getChild(i);
                if (!(c instanceof RuleContext)) continue;
                int ri = ((RuleContext) c).getRuleIndex();
                if (ri >= 0 && ri < PlSqlParser.ruleNames.length) {
                    if (PlSqlParser.ruleNames[ri].equals("statement")) {
                        result.add(c);
                    }
                } else {
                    stack.push(c);
                }
            }
        }
        return result;
    }

    static ParseTree findFirstExecutableUnit(ParseTree root) {
        if (root == null) return null;
        // BFS through tree, searching in unnamed subcontexts
        java.util.Stack<ParseTree> stack = new java.util.Stack<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            ParseTree cur = stack.pop();
            for (int i = 0; i < cur.getChildCount(); i++) {
                ParseTree c = cur.getChild(i);
                if (!(c instanceof RuleContext)) continue;
                int ri = ((RuleContext) c).getRuleIndex();
                if (ri >= 0 && ri < PlSqlParser.ruleNames.length) {
                    String rn = PlSqlParser.ruleNames[ri];
                    // Direct match: this IS an executable unit
                    if (rn.equals("create_procedure_body")
                        || rn.equals("create_function_body")
                        || rn.equals("anonymous_block")
                        || rn.equals("create_package_body")) {
                        return c;
                    }
                    // unit_statement: check its direct rule children
                    if (rn.equals("unit_statement")) {
                        for (int j = 0; j < c.getChildCount(); j++) {
                            ParseTree uc = c.getChild(j);
                            if (!(uc instanceof RuleContext)) continue;
                            int uri = ((RuleContext) uc).getRuleIndex();
                            if (uri < 0 || uri >= PlSqlParser.ruleNames.length) continue;
                            String urn = PlSqlParser.ruleNames[uri];
                            if (urn.equals("create_procedure_body")
                                || urn.equals("create_function_body")
                                || urn.equals("anonymous_block")
                                || urn.equals("create_package_body"))
                                return uc;
                        }
                    }
                } else {
                    // Unnamed subcontext: recurse
                    stack.push(c);
                }
            }
        }
        return null;
    }

    static String extractEntryName(ParseTree node) {
        if (node == null) return "ANONYMOUS BLOCK";
        String rn = PlSqlParser.ruleNames[((RuleContext) node).getRuleIndex()];
        boolean isFunc = rn.contains("function");
        for (int i = 0; i < node.getChildCount(); i++) {
            ParseTree c = node.getChild(i);
            if (c instanceof TerminalNode) {
                String t = c.getText().toUpperCase();
                if (t.equals("PROCEDURE") || t.equals("FUNCTION")) {
                    if (i + 1 < node.getChildCount()) {
                        ParseTree next = node.getChild(i + 1);
                        String name = next instanceof RuleContext
                            ? next.getText().trim().split("\\\\s")[0]
                            : next.getText();
                        if (name.matches("[a-zA-Z_#][a-zA-Z0-9_#$]*"))
                            return isFunc ? "FUNCTION " + name : "PROCEDURE " + name;
                    }
                }
            }
        }
        return isFunc ? "FUNCTION" : "PROCEDURE";
    }

    static String extractLoopLabel(ParseTree node) {
        for (int i = 0; i < node.getChildCount(); i++) {
            ParseTree c = node.getChild(i);
            if (c instanceof TerminalNode) {
                String t = c.getText().toUpperCase();
                if (t.equals("WHILE")) {
                    String cond = extractRuleText(node, "condition");
                    return "WHILE " + (cond.isEmpty() ? "..." : cond);
                } else if (t.equals("FOR")) {
                    String param = extractRuleText(node, "cursor_loop_param");
                    return "FOR " + (param.isEmpty() ? "..." : truncate(param, 19));
                }
            }
        }
        return "LOOP";
    }

    static String extractHandlerName(ParseTree handlerNode) {
        StringBuilder sb = new StringBuilder("WHEN ");
        for (int i = 0; i < handlerNode.getChildCount(); i++) {
            ParseTree c = handlerNode.getChild(i);
            if (c instanceof RuleContext) {
                int ri = ((RuleContext) c).getRuleIndex();
                if (ri >= 0 && ri < PlSqlParser.ruleNames.length
                    && PlSqlParser.ruleNames[ri].equals("exception_name")) {
                    if (sb.length() > 5) sb.append(" OR ");
                    sb.append(c.getText().trim());
                }
            } else if (c instanceof TerminalNode && c.getText().equalsIgnoreCase("THEN"))
                break;
        }
        return sb.toString().trim();
    }

    // Extract original text from source using token positions (preserves whitespace)
    static String getOriginalText(ParseTree node) {
        if (node instanceof ParserRuleContext) {
            ParserRuleContext ctx = (ParserRuleContext) node;
            if (ctx.start != null && ctx.stop != null) {
                int start = ctx.start.getStartIndex();
                int stop = ctx.stop.getStopIndex();
                if (start >= 0 && stop < currentSource.length()) {
                    return currentSource.substring(start, stop + 1);
                }
            }
        }
        return node.getText();
    }

    static String generateEmptyFallback() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
             + "<mxfile host=\"Flowchart\" modified=\"2026\" agent=\"plsql\" type=\"device\">\n"
             + "  <diagram name=\"Flowchart\" id=\"d1\">\n"
             + "    <mxGraphModel pageWidth=\"400\" pageHeight=\"300\" background=\"#f8f9fa\">\n"
             + "      <root>\n"
             + "        <mxCell id=\"0\"/>\n"
             + "        <mxCell id=\"1\" parent=\"0\"/>\n"
             + "        <mxCell id=\"2\" value=\"No flow found\" vertex=\"1\" parent=\"1\""
             + " style=\"rounded=1;whiteSpace=wrap;html=1;fontSize=10;fontColor=#ffffff;fillColor=#909399;\">\n"
             + "          <mxGeometry x=\"100\" y=\"100\" width=\"200\" height=\"36\" as=\"geometry\"/>\n"
             + "        </mxCell>\n"
             + "      </root>\n"
             + "    </mxGraphModel>\n"
             + "  </diagram>\n"
             + "</mxfile>\n";
    }

    // ================================================================
    //  Layout & drawio generation
    // ================================================================

    static String generateDrawio(FlowGraph g) {
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

        Map<Integer, List<String>> rankNodes = new TreeMap<>();
        for (String nid : ranks.keySet()) {
            rankNodes.computeIfAbsent(ranks.get(nid), k -> new ArrayList<>()).add(nid);
        }

        Map<String, Double> nodeXs = new HashMap<>();
        Map<String, Double> nodeYs = new HashMap<>();

        for (Map.Entry<Integer, List<String>> entry : rankNodes.entrySet()) {
            int rank = entry.getKey();
            List<String> nids = entry.getValue();
            double xOff = PAD;
            for (String nid : nids) {
                FlowNode fn = findNode(g, nid);
                if (fn == null) continue;
                nodeXs.put(nid, xOff);
                nodeYs.put(nid, (double) (PAD + rank * (NODE_H + V_GAP)));
                xOff += fn.w + BRANCH_H_GAP;
            }
        }

        double totalW = PAD;
        for (Map.Entry<Integer, List<String>> entry : rankNodes.entrySet()) {
            List<String> nids = entry.getValue();
            double lastX = PAD;
            for (String nid : nids) {
                FlowNode fn = findNode(g, nid);
                if (fn == null) continue;
                lastX = nodeXs.get(nid) + fn.w;
            }
            totalW = Math.max(totalW, lastX + PAD);
        }
        int lastRank = rankNodes.isEmpty() ? 0 : ((TreeMap<Integer, List<String>>) rankNodes).lastKey();
        double totalH = PAD + (lastRank + 1) * (NODE_H + V_GAP) + PAD;

        // Make merge nodes visible (small circles)
        for (FlowNode fn : g.nodes) {
            if (fn.type == NodeType.MERGE) {
                fn.w = 8;
                fn.h = 8;
            }
        }


        // Rule 7: Shift loop body + exception handler nodes right
        for (FlowNode fn : g.nodes) {
            // Shift exception handler blocks right
            if (fn.type == NodeType.EXCEPTION) {
                double off = 250;
                // BFS to find all handler nodes (reachable from this exception node)
                java.util.Set<String> excNodes = new java.util.HashSet<>();
                java.util.Queue<String> q3 = new java.util.LinkedList<>();
                q3.add(fn.id);
                excNodes.add(fn.id);
                while (!q3.isEmpty()) {
                    String cur = q3.poll();
                    for (FlowEdge e : g.edges) {
                        if (e.fromId.equals(cur) && !excNodes.contains(e.toId)) {
                            excNodes.add(e.toId);
                            q3.add(e.toId);
                        }
                    }
                }
                for (String nid : excNodes) {
                    if (nodeXs.containsKey(nid)) {
                        nodeXs.put(nid, nodeXs.get(nid) + off);
                    }
                }
                break; // only one exception block
            }
        }
        for (FlowNode fn : g.nodes) {
            if (fn.type != NodeType.LOOP_BOUNDARY) continue;
            // Find body entry (non-Exit edge) and exit node (Exit edge)
            String bodyEntry = null, exitNode = null;
            for (FlowEdge e : g.edges) {
                if (e.fromId.equals(fn.id)) {
                    if ("Exit".equals(e.label)) exitNode = e.toId;
                    else bodyEntry = e.toId;
                }
            }
            if (bodyEntry == null || !nodeXs.containsKey(bodyEntry)) continue;
            
            // BFS to find all body nodes
            java.util.Set<String> bodyNodes = new java.util.HashSet<>();
            java.util.Queue<String> q2 = new java.util.LinkedList<>();
            q2.add(bodyEntry);
            bodyNodes.add(bodyEntry);
            while (!q2.isEmpty()) {
                String cur = q2.poll();
                for (FlowEdge e : g.edges) {
                    if (e.fromId.equals(cur) && !bodyNodes.contains(e.toId)) {
                        bodyNodes.add(e.toId);
                        q2.add(e.toId);
                    }
                }
            }
            if (exitNode != null) bodyNodes.remove(exitNode);
            bodyNodes.remove(fn.id);
            
            // Shift right
            double offset = 200;
            for (String nid : bodyNodes) {
                if (nodeXs.containsKey(nid)) {
                    nodeXs.put(nid, nodeXs.get(nid) + offset);
                }
            }
        }
        // Center post-merge flow: propagate merge center x to next sequential nodes
        for (FlowNode fn : g.nodes) {
            if (fn.type != NodeType.MERGE) continue;
            double mergeCenterX = nodeXs.getOrDefault(fn.id, 0.0) + fn.w / 2;
            int mr = ranks.getOrDefault(fn.id, 0);
            // Find the next node(s) that are reached from this merge
            List<String> nextIds = new ArrayList<>();
            for (FlowEdge e : g.edges) {
                if (e.fromId.equals(fn.id) && nextIds.contains(e.toId)) continue;
                if (e.fromId.equals(fn.id)) nextIds.add(e.toId);
            }
            if (!nextIds.isEmpty()) {
                double branchTotalW = 0;
                for (String nid : nextIds) {
                    FlowNode nfn = findNode(g, nid);
                    if (nfn != null) totalW += nfn.w + BRANCH_H_GAP;
                }
                if (branchTotalW > 0) branchTotalW -= BRANCH_H_GAP;
                double startX = mergeCenterX - branchTotalW / 2;
                for (String nid : nextIds) {
                    FlowNode nfn = findNode(g, nid);
                    if (nfn == null) continue;
                    nodeXs.put(nid, startX);
                    startX += nfn.w + BRANCH_H_GAP;
                }
            }
        }

        // Center decision branches
        for (FlowNode fn : g.nodes) {
            if (fn.type != NodeType.DECISION) continue;
            List<String> children = new ArrayList<>();
            for (FlowEdge e : g.edges) {
                if (e.fromId.equals(fn.id) && !e.label.isEmpty()) children.add(e.toId);
            }
            if (children.size() < 2) continue;
            double totalW2 = 0;
            for (String cid : children) {
                FlowNode cn = findNode(g, cid);
                if (cn != null) totalW2 += cn.w + BRANCH_H_GAP;
            }
            if (totalW2 > 0) totalW2 -= BRANCH_H_GAP;
            double fnX = nodeXs.getOrDefault(fn.id, 0.0);
            FlowNode fnObj = findNode(g, fn.id);
            double centerX = fnX + (fnObj != null ? fnObj.w / 2 : 80);
            double childStart = centerX - totalW2 / 2;
            for (String cid : children) {
                FlowNode cn = findNode(g, cid);
                if (cn == null) continue;
                nodeXs.put(cid, childStart);
                childStart += cn.w + BRANCH_H_GAP;
            }
        }

        StringBuilder sb = new StringBuilder();
        int cid = 2;
        Map<String, String> idMap = new HashMap<>();

        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<mxfile host=\"Flowchart\" modified=\"2026\" agent=\"plsql-flow\" type=\"device\">\n");
        sb.append("  <diagram name=\"PL/SQL Flowchart\" id=\"d1\">\n");
        sb.append("    <mxGraphModel pageWidth=\"").append(fmt(totalW)).append("\"")
          .append(" pageHeight=\"").append(fmt(totalH)).append("\"")
          .append(" background=\"").append(C_BG).append("\"")
          .append(" grid=\"1\" gridSize=\"10\">\n");
        sb.append("      <root>\n");
        sb.append("        <mxCell id=\"0\"/>\n");
        sb.append("        <mxCell id=\"1\" parent=\"0\"/>\n");

        for (FlowNode fn : g.nodes) {
            Double x = nodeXs.get(fn.id);
            Double y = nodeYs.get(fn.id);
            if (x == null || y == null) continue;
            // MERGE nodes are now rendered as small dots below
            String vid = String.valueOf(cid++);
            idMap.put(fn.id, vid);
            String fillColor;
            String shape = "rounded=1";
            String extra = "";
            switch (fn.type) {
                case START:    fillColor = C_START; shape = "ellipse"; extra = ";fontStyle=4"; break;
                case END:      fillColor = C_END; shape = "ellipse"; break;
                case DECISION: fillColor = C_DECISION; shape = "rhombus"; extra = ";fontStyle=4"; break;
                case LOOP_BOUNDARY: fillColor = C_LOOP; extra = ";fontStyle=4"; break;
                case EXCEPTION: fillColor = C_EXCEPTION; extra = ";fontStyle=4"; break;
                case MERGE:    fillColor = "#adb5bd"; shape = "ellipse"; fn.w = 10; fn.h = 10; extra = ""; break;
                default: fillColor = C_PROCESS; break;
            }
            String st = shape + ";whiteSpace=wrap;html=1;fontSize=" + FONT_SZ
                + ";fontColor=" + C_TEXT + ";fillColor=" + fillColor
                + ";strokeColor=none;overflow=hidden" + extra;
            sb.append("        <mxCell id=\"").append(vid).append("\"")
              .append(" value=\"").append(xmlEsc(fn.label)).append("\"")
              .append(" vertex=\"1\" parent=\"1\"")
              .append(" style=\"").append(st).append("\">\n");
            sb.append("          <mxGeometry")
              .append(" x=\"").append(fmt(x)).append("\"")
              .append(" y=\"").append(fmt(y)).append("\"")
              .append(" width=\"").append(fmt(fn.w)).append("\"")
              .append(" height=\"").append(fmt(fn.h)).append("\"")
              .append(" as=\"geometry\"/>\n");
            sb.append("        </mxCell>\n");
        }

        String edgeBaseStyle = "edgeStyle=orthogonalEdgeStyle;strokeColor=" + C_EDGE
            + ";strokeWidth=1;rounded=1;fontSize=" + FONT_SZ_SM + ";verticalAlign=middle;";

        for (FlowEdge fe : g.edges) {
            String src = idMap.get(fe.fromId);
            String tgt = idMap.get(fe.toId);
            if (src == null || tgt == null) continue;
            String eid = String.valueOf(cid++);
            sb.append("        <mxCell id=\"").append(eid).append("\"")
              .append(" edge=\"1\" parent=\"1\"")
              .append(" source=\"").append(src).append("\"")
              .append(" target=\"").append(tgt).append("\"");
            if (!fe.label.isEmpty() && !"Exit".equals(fe.label)) {
                sb.append(" style=\"").append(edgeBaseStyle)
                  .append("labelBackgroundColor=#ffffff;\">\n");
                sb.append("          <mxGeometry relative=\"1\" as=\"geometry\"/>\n");
                sb.append("        </mxCell>\n");
                String lid = String.valueOf(cid++);
                sb.append("        <mxCell id=\"").append(lid).append("\"")
                  .append(" value=\"").append(xmlEsc(fe.label)).append("\"")
                  .append(" vertex=\"1\" parent=\"1\"")
                  .append(" style=\"edgeLabel;html=1;fontSize=9;labelBackgroundColor=#ffffff;\"")
                  .append(" connectable=\"0\">\n");
                sb.append("          <mxGeometry x=\"-0.5\" y=\"0\" relative=\"1\" as=\"geometry\">\n");
                sb.append("            <mxPoint as=\"offset\"/>\n");
                sb.append("          </mxGeometry>\n");
                sb.append("        </mxCell>\n");
            } else {
                sb.append(" style=\"").append(edgeBaseStyle).append("\"/>\n");
            }
        }

        sb.append("      </root>\n");
        sb.append("    </mxGraphModel>\n");
        sb.append("  </diagram>\n");
        sb.append("</mxfile>\n");
        return sb.toString();
    }

    static FlowNode findNode(FlowGraph g, String id) {
        for (FlowNode n : g.nodes) if (n.id.equals(id)) return n;
        return null;
    }

    static String fmt(double d) {
        return d == (int)d ? String.valueOf((int)d) : String.format("%.1f", d);
    }


    // Case-insensitive CharStream wrapper for ANTLR 4.9.3
    // Uppercases characters for lexer matching but preserves original text
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

    static String xmlEsc(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;")
                .replace(">","&gt;").replace("\"","&quot;")
                .replace("'","&apos;");
    }
}
