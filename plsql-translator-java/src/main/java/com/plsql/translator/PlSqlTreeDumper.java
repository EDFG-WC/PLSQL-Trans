package com.plsql.translator;

import com.plsql.translator.parser.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

import java.nio.file.*;

/**
 * 调试工具：打印 PL/SQL 解析树的全结构
 */
public class PlSqlTreeDumper {

    public static void main(String[] args) throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(args[0])));

        CharStream input = CharStreams.fromString(source);
        PlSqlLexer lexer = new PlSqlLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        PlSqlParser parser = new PlSqlParser(tokens);
        parser.removeErrorListeners();

        ParseTree tree = parser.sql_script();
        dump(tree, 0);
    }

    static void dump(ParseTree node, int indent) {
        String prefix = "";
        for (int i = 0; i < indent; i++) prefix += "  ";

        if (node instanceof TerminalNode) {
            TerminalNode tn = (TerminalNode) node;
            String text = tn.getText().replace("\n", "\\n").replace("\r", "\\r");
            if (text.length() > 80) text = text.substring(0, 77) + "...";
            System.out.println(prefix + "TERM: " + text);
        } else if (node instanceof RuleContext) {
            RuleContext rc = (RuleContext) node;
            String name = PlSqlParser.ruleNames[rc.getRuleIndex()];
            System.out.println(prefix + "RULE: " + name + "  [" + rc.getText().length() + " chars]");
            for (int i = 0; i < node.getChildCount(); i++) {
                dump(node.getChild(i), indent + 1);
            }
        }
    }
}
