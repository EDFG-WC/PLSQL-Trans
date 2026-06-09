package com.plsql.translator;

import com.plsql.translator.parser.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * PL/SQL 解析树 → JSON AST 转换器。
 */
public class PlSqlAstToJson {

    public static void main(String[] args) throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(args[0])));
        String json = plsqlToJson(source);
        if (args.length >= 2) {
            Files.write(Paths.get(args[1]), json.getBytes());
        } else {
            System.out.println(json);
        }
    }

    /** 公共方法：PL/SQL → JSON AST 字符串 */
    public static String plsqlToJson(String plsql) {
        CharStream ci = CharStreams.fromString(plsql);
        PlSqlLexer lex = new PlSqlLexer(ci);
        PlSqlParser par = new PlSqlParser(new CommonTokenStream(lex));
        par.removeErrorListeners();
        ParseTree tree = par.sql_script();
        return toJson(tree, par);
    }

    public static String toJson(ParseTree tree, PlSqlParser parser) {
        StringBuilder sb = new StringBuilder();
        toJson(tree, parser, sb, 0);
        return sb.toString();
    }

    private static void toJson(ParseTree node, PlSqlParser parser,
                                StringBuilder sb, int depth) {
        if (node instanceof TerminalNode) {
            TerminalNode tn = (TerminalNode) node;
            String text = tn.getText();
            if (text.trim().isEmpty() && !"\n".equals(text)) {
                sb.append("null");
                return;
            }
            sb.append("{\"token\":").append(jsonStr(text)).append("}");
            return;
        }

        RuleContext ctx = (RuleContext) node;
        String ruleName = parser.ruleNames[ctx.getRuleIndex()];
        sb.append("{\"rule\":").append(jsonStr(ruleName));

        int childCount = node.getChildCount();
        boolean hasChildren = false;
        for (int i = 0; i < childCount; i++) {
            ParseTree child = node.getChild(i);
            if (child instanceof TerminalNode && child.getText().trim().isEmpty()) continue;
            if (child instanceof RuleContext) {
                String rn = parser.ruleNames[((RuleContext)child).getRuleIndex()];
                if (rn.equals("sql_plus_command") || rn.equals("sql_plus_command_non_reserved_word")) continue;
            }
            hasChildren = true;
            break;
        }

        if (hasChildren) {
            sb.append(",\"children\":[");
            boolean first = true;
            for (int i = 0; i < childCount; i++) {
                ParseTree child = node.getChild(i);
                if (child instanceof TerminalNode && child.getText().trim().isEmpty()) continue;
                int before = sb.length();
                toJson(child, parser, sb, depth + 1);
                String sub = sb.substring(before);
                if ("null".equals(sub)) continue;
                if (!first) sb.append(',');
                first = false;
            }
            sb.append(']');
        }
        sb.append('}');
    }

    private static String jsonStr(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:   sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
