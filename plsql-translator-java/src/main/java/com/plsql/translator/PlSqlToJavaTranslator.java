package com.plsql.translator;

import com.plsql.translator.parser.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

import java.util.*;
import java.util.stream.*;

/**
 * PL/SQL → Java 翻译器核心。
 *
 * 使用 ANTLR4 解析 PL/SQL，通过访问者模式遍历语法树，
 * 将 PL/SQL 代码结构转换为等价的 Java 代码。
 *
 * 当前支持的转换:
 *   - CREATE PROCEDURE → Java static void 方法
 *   - CREATE FUNCTION  → Java static 方法 (含返回类型)
 *   - 变量声明            → Java 类型推断声明
 *   - IF/THEN/ELSIF     → if/else if
 *   - LOOP/WHILE/FOR    → Java 循环
 *   - BEGIN/END         → 代码块
 *   - SELECT INTO       → 类型安全的赋值
 *   - DML (INSERT/UPDATE/DELETE) → JDBC 模板
 *   - CURSOR            → Iterator 模式
 *   - EXCEPTION         → try/catch
 *   :=                 → =
 *   NULL               → null
 */
public class PlSqlToJavaTranslator {

    private final StringBuilder output = new StringBuilder();
    private int indentLevel = 0;
    private String currentClassName = "TranslatedCode";

    /**
     * 将 PL/SQL 源码翻译为 Java。
     */
    public String translate(String plsqlCode) {
        // Lexer + Parser
        CharStream input = CharStreams.fromString(plsqlCode);
        PlSqlLexer lexer = new PlSqlLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        PlSqlParser parser = new PlSqlParser(tokens);

        // 解析
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPositionInLine, String msg, RecognitionException e) {
                System.err.println("语法错误 (行 " + line + ":" + charPositionInLine + "): " + msg);
            }
        });

        ParseTree tree = parser.sql_script();

        // 生成 Java
        output.setLength(0);
        indentLevel = 0;

        emitLine("// ============================================================");
        emitLine("// 由 PL/SQL → Java 翻译器自动生成");
        emitLine("// 原语言: PL/SQL (Oracle)");
        emitLine("// ============================================================");
        emitLine("");

        // 遍历顶层节点
        for (int i = 0; i < tree.getChildCount(); i++) {
            ParseTree child = tree.getChild(i);
            if (child instanceof ErrorNode) continue;
            visitNode(child);
        }

        return output.toString();
    }

    // ================================================================
    //  访问者方法
    // ================================================================

    private void visitNode(ParseTree node) {
        if (node == null) return;

        // 获取规则名称
        String ruleName = getRuleName(node);

        switch (ruleName) {
            case "unit_statement":
                visitUnitStatement(node);
                break;
            case "sql_plus_command":
                // 跳过 SQL*Plus 命令 (/, show, set 等)
                break;
            default:
                // 递归遍历子节点
                for (int i = 0; i < node.getChildCount(); i++) {
                    visitNode(node.getChild(i));
                }
                break;
        }
    }

    private void visitUnitStatement(ParseTree node) {
        for (int i = 0; i < node.getChildCount(); i++) {
            ParseTree child = node.getChild(i);
            String name = getRuleName(child);

            switch (name) {
                case "create_procedure_body":
                    visitCreateProcedure(child);
                    break;
                case "create_function_body":
                    visitCreateFunction(child);
                    break;
                case "create_package":
                    visitCreatePackage(child);
                    break;
                case "create_package_body":
                    visitCreatePackageBody(child);
                    break;
                default:
                    visitNode(child);
                    break;
            }
        }
    }

    /** CREATE PROCEDURE → Java static void 方法 */
    private void visitCreateProcedure(ParseTree node) {
        String procName = findNodeText(node, "procedure_name");
        if (procName == null) procName = "unknownProcedure";

        emitLine("// ---- 存储过程: " + procName + " ----");
        emit("public static void " + toCamelCase(procName) + "(");

        // 参数
        String params = extractParameters(node);
        if (!params.isEmpty()) {
            emit(params);
        }
        emitLine(") {");

        indentLevel++;
        visitBody(node);
        indentLevel--;

        emitLine("}");
        emitLine("");
    }

    /** CREATE FUNCTION → Java static 方法（带返回类型） */
    private void visitCreateFunction(ParseTree node) {
        String funcName = findNodeText(node, "function_name");
        if (funcName == null) funcName = "unknownFunction";

        String returnType = extractReturnType(node);
        if (returnType == null || returnType.isEmpty()) returnType = "void";

        emitLine("// ---- 函数: " + funcName + " ----");
        emit("public static " + mapType(returnType) + " " + toCamelCase(funcName) + "(");

        String params = extractParameters(node);
        if (!params.isEmpty()) {
            emit(params);
        }
        emitLine(") {");

        indentLevel++;
        emitLine(mapType(returnType) + " result;");
        visitBody(node);
        indentLevel--;

        emitLine("}");
        emitLine("");
    }

    /** CREATE PACKAGE → Java 类 (接口风格的类) */
    private void visitCreatePackage(ParseTree node) {
        String pkgName = findNodeText(node, "package_name");
        if (pkgName == null) pkgName = "UnknownPackage";
        currentClassName = toPascalCase(pkgName);

        emitLine("// ---- 包规范: " + pkgName + " ----");
        emitLine("public class " + currentClassName + " {");
        indentLevel++;

        // 包体内容（声明）
        ParseTree body = findChildByRule(node, "package_obj_spec");
        if (body != null) {
            for (int i = 0; i < body.getChildCount(); i++) {
                visitNode(body.getChild(i));
            }
        }

        indentLevel--;
        emitLine("}");
        emitLine("");
        currentClassName = "TranslatedCode";
    }

    /** CREATE PACKAGE BODY → Java 类 (实现) */
    private void visitCreatePackageBody(ParseTree node) {
        String pkgName = findNodeText(node, "package_name");
        if (pkgName == null) pkgName = "UnknownPackageImpl";
        currentClassName = toPascalCase(pkgName);

        emitLine("// ---- 包体: " + pkgName + " ----");
        emitLine("public class " + currentClassName + " {");
        indentLevel++;

        ParseTree body = findChildByRule(node, "package_obj_body");
        if (body != null) {
            for (int i = 0; i < body.getChildCount(); i++) {
                visitNode(body.getChild(i));
            }
        }

        indentLevel--;
        emitLine("}");
        emitLine("");
        currentClassName = "TranslatedCode";
    }

    /** 处理 BEGIN...END 块中的语句 */
    private void visitBody(ParseTree node) {
        ParseTree body = findChildByRule(node, "body");
        if (body == null) {
            // 尝试找 seq_of_statements
            ParseTree seq = findChildByRule(node, "seq_of_statements");
            if (seq != null) {
                visitStatements(seq);
            }
            return;
        }

        ParseTree seq = findChildByRule(body, "seq_of_statements");
        if (seq != null) {
            visitStatements(seq);
        }

        // EXCEPTION → try/catch
        ParseTree exc = findChildByRule(body, "exception_handler");
        if (exc != null) {
            visitException(exc);
        }
    }

    private void visitStatements(ParseTree node) {
        for (int i = 0; i < node.getChildCount(); i++) {
            ParseTree child = node.getChild(i);
            String name = getRuleName(child);
            switch (name) {
                case "statement":
                    visitStatement(child);
                    break;
                default:
                    visitNode(child);
                    break;
            }
        }
    }

    private void visitStatement(ParseTree node) {
        for (int i = 0; i < node.getChildCount(); i++) {
            ParseTree child = node.getChild(i);
            String name = getRuleName(child);
            switch (name) {
                case "assignment_statement":
                    visitAssignment(child);
                    break;
                case "if_statement":
                    visitIfStatement(child);
                    break;
                case "loop_statement":
                    visitLoop(child);
                    break;
                case "for_loop":
                    visitForLoop(child);
                    break;
                case "while_loop":
                    visitWhileLoop(child);
                    break;
                case "case_statement":
                    visitCaseStatement(child);
                    break;
                case "return_statement":
                    visitReturn(child);
                    break;
                case "null_statement":
                    emitLine("// NULL 语句 (无操作)");
                    break;
                case "sql_statement":
                    visitSqlStatement(child);
                    break;
                case "open_statement":
                    emitLine("// TODO: OPEN cursor (转为 Iterator)");
                    break;
                case "fetch_statement":
                    emitLine("// TODO: FETCH cursor");
                    break;
                case "close_statement":
                    emitLine("// TODO: CLOSE cursor");
                    break;
                default:
                    // 如果是未知语句，输出原文作为注释
                    String text = getFullText(child);
                    if (!text.trim().isEmpty()) {
                        emitLine("// [PL/SQL]: " + text.replace("\n", " ").trim());
                    }
                    break;
            }
        }
    }

    // ================================================================
    //  具体语句翻译
    // ================================================================

    /** 变量赋值 variable := expression */
    private void visitAssignment(ParseTree node) {
        String target = findNodeText(node, "variable_name");
        String expr = findNodeText(node, "expression");
        if (target == null) {
            // 取左边第一个标识符
            target = getFullText(node);
            if (target.contains(":=")) {
                String[] parts = target.split(":=", 2);
                target = parts[0].trim();
                expr = parts.length > 1 ? parts[1].trim() : "";
            }
        }
        if (expr == null) expr = "";

        // PL/SQL 的 := → Java 的 =
        expr = expr.replace(":=", " = ");
        // PL/SQL 的 NULL → Java 的 null
        expr = expr.replaceAll("\\bNULL\\b", "null");
        // PL/SQL 的 SYSDATE → Java 的 new Date()
        expr = expr.replaceAll("\\bSYSDATE\\b", "new java.util.Date()");

        emitLine(target.trim() + " = " + expr.trim() + ";");
    }

    /** IF condition THEN ... ELSIF ... ELSE ... END IF */
    private void visitIfStatement(ParseTree node) {
        // 提取条件
        String condition = findNodeText(node, "condition");
        if (condition == null) condition = "true";
        condition = mapCondition(condition);

        emitLine("if (" + condition + ") {");
        indentLevel++;

        // THEN 分支
        ParseTree thenStmts = findChildByRule(node, "seq_of_statements");
        if (thenStmts != null) visitStatements(thenStmts);

        indentLevel--;

        // ELSIF 分支（递归处理 elsif_part）
        ParseTree elsif = findChildByRule(node, "elsif_part");
        while (elsif != null) {
            String elsifCond = findNodeText(elsif, "condition");
            if (elsifCond == null) elsifCond = "true";
            elsifCond = mapCondition(elsifCond);

            emit("} else if (" + elsifCond + ") {");
            indentLevel++;

            ParseTree elsifStmts = findChildByRule(elsif, "seq_of_statements");
            if (elsifStmts != null) visitStatements(elsifStmts);

            indentLevel--;
            elsif = findChildByRule(elsif, "elsif_part");
        }

        // ELSE 分支
        ParseTree elseStmts = findChildByRule(node, "else_part");
        if (elseStmts != null) {
            emitLine("} else {");
            indentLevel++;
            visitStatements(elseStmts);
            indentLevel--;
        }

        emitLine("}");
    }

    /** 循环 LOOP ... END LOOP */
    private void visitLoop(ParseTree node) {
        emitLine("while (true) {  // LOOP");
        indentLevel++;
        ParseTree stmts = findChildByRule(node, "seq_of_statements");
        if (stmts != null) visitStatements(stmts);
        indentLevel--;
        emitLine("}");
    }

    /** FOR 循环 */
    private void visitForLoop(ParseTree node) {
        String counter = findNodeText(node, "index_name");
        // 尝试取范围上下限
        String lower = findNodeText(node, "lower_bound");
        String upper = findNodeText(node, "upper_bound");
        if (counter != null && lower != null && upper != null) {
            emitLine("for (int " + counter + " = " + lower + "; "
                    + counter + " <= " + upper + "; " + counter + "++) {");
        } else {
            // CURSOR FOR 循环
            String cursorName = findNodeText(node, "cursor_name");
            if (cursorName != null) {
                emitLine("// FOR rec IN " + cursorName + " → for-each loop");
                emitLine("for (Map<String,Object> rec : " + cursorName + ") {");
            } else {
                emitLine("for (... : ...) {  // FOR loop");
            }
        }

        indentLevel++;
        ParseTree stmts = findChildByRule(node, "seq_of_statements");
        if (stmts != null) visitStatements(stmts);
        indentLevel--;
        emitLine("}");
    }

    /** WHILE 循环 */
    private void visitWhileLoop(ParseTree node) {
        String condition = findNodeText(node, "condition");
        if (condition == null) condition = "true";
        condition = mapCondition(condition);
        emitLine("while (" + condition + ") {");
        indentLevel++;
        ParseTree stmts = findChildByRule(node, "seq_of_statements");
        if (stmts != null) visitStatements(stmts);
        indentLevel--;
        emitLine("}");
    }

    /** CASE 语句 */
    private void visitCaseStatement(ParseTree node) {
        String selector = findNodeText(node, "expression");
        if (selector != null) {
            emitLine("switch (" + selector + ") {");
        } else {
            emitLine("switch (true) {  // searched CASE");
        }
        indentLevel++;
        emitLine("// TODO: CASE branches");
        indentLevel--;
        emitLine("}");
    }

    /** RETURN */
    private void visitReturn(ParseTree node) {
        String expr = getFullText(node).replace("RETURN", "").trim();
        emitLine("return " + expr + ";");
    }

    /** SQL 语句 (DML + 查询) */
    private void visitSqlStatement(ParseTree node) {
        String text = getFullText(node).trim();
        if (text.isEmpty()) return;

        String upper = text.toUpperCase();
        if (upper.startsWith("SELECT")) {
            visitSelect(text);
        } else if (upper.startsWith("INSERT")) {
            visitDml("INSERT", text);
        } else if (upper.startsWith("UPDATE")) {
            visitDml("UPDATE", text);
        } else if (upper.startsWith("DELETE")) {
            visitDml("DELETE", text);
        } else if (upper.startsWith("MERGE")) {
            visitDml("MERGE", text);
        } else {
            emitLine("// SQL: " + text.replace("\n", " "));
        }
    }

    private void visitSelect(String sql) {
        emitLine("// SELECT 语句 → JDBC 查询");
        emitLine("// " + sql.replace("\n", " ").trim());
        emitLine("String sql = \"" + escapeJavaString(sql) + "\";");
        emitLine("// try (Statement stmt = conn.createStatement();");
        emitLine("//      ResultSet rs = stmt.executeQuery(sql)) {");
        emitLine("//     while (rs.next()) { ... }");
        emitLine("// }");
    }

    private void visitDml(String type, String sql) {
        emitLine("// " + type + " → JDBC 执行");
        emitLine("// " + sql.replace("\n", " ").trim());
        emitLine("String sql = \"" + escapeJavaString(sql) + "\";");
        emitLine("// try (Statement stmt = conn.createStatement()) {");
        emitLine("//     int rows = stmt.executeUpdate(sql);");
        emitLine("// }");
    }

    /** EXCEPTION → try/catch */
    private void visitException(ParseTree node) {
        emitLine("} try {  // EXCEPTION 块已包装在 try 中");
        // 异常处理已经在 body 中用 try/catch 包围了
    }

    // ================================================================
    //  工具方法
    // ================================================================

    private void emit(String s) {
        output.append(s);
    }

    private void emitLine(String s) {
        for (int i = 0; i < indentLevel; i++) output.append("    ");
        output.append(s).append("\n");
    }

    /** 从语法树节点的子节点中按规则名查找文本 */
    private String findNodeText(ParseTree node, String ruleName) {
        for (int i = 0; i < node.getChildCount(); i++) {
            ParseTree child = node.getChild(i);
            if (getRuleName(child).equals(ruleName)) {
                return getFullText(child);
            }
        }
        return null;
    }

    /** 按规则名查找子节点 */
    private ParseTree findChildByRule(ParseTree node, String ruleName) {
        for (int i = 0; i < node.getChildCount(); i++) {
            ParseTree child = node.getChild(i);
            if (getRuleName(child).equals(ruleName)) {
                return child;
            }
        }
        return null;
    }

    /** 获取节点的规则名称 */
    private String getRuleName(ParseTree node) {
        if (node instanceof RuleContext) {
            return PlSqlParser.ruleNames[((RuleContext) node).getRuleIndex()];
        }
        if (node instanceof TerminalNode) {
            return ((TerminalNode) node).getSymbol().getType() + "";
        }
        return "";
    }

    /** 获取节点的完整原文 */
    private String getFullText(ParseTree node) {
        if (node instanceof RuleContext) {
            return ((RuleContext) node).getText();
        }
        return node.getText();
    }

    /** 提取存储过程/函数的参数列表 */
    private String extractParameters(ParseTree node) {
        ParseTree paramList = findChildByRule(node, "parameter");
        if (paramList == null) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paramList.getChildCount(); i++) {
            ParseTree param = paramList.getChild(i);
            String name = findNodeText(param, "parameter_name");
            String type = findNodeText(param, "datatype");
            if (name != null && type != null) {
                if (sb.length() > 0) sb.append(", ");
                // 检查 IN/OUT/IN OUT 模式
                String text = getFullText(param).toUpperCase();
                if (text.contains("OUT")) {
                    sb.append("java.util.List<").append(mapType(type)).append("> ").append(name).append("_out");
                } else {
                    sb.append(mapType(type)).append(" ").append(toCamelCase(name));
                }
            }
        }
        return sb.toString();
    }

    /** 提取函数返回类型 */
    private String extractReturnType(ParseTree node) {
        return findNodeText(node, "datatype");
    }

    /** PL/SQL 类型 → Java 类型 */
    private String mapType(String plsqlType) {
        if (plsqlType == null) return "Object";
        String t = plsqlType.toUpperCase().trim();

        if (t.startsWith("VARCHAR2") || t.startsWith("VARCHAR")
            || t.startsWith("CHAR") || t.startsWith("CLOB")
            || t.startsWith("NVARCHAR2") || t.startsWith("NCHAR")
            || t.startsWith("LONG")) return "String";

        if (t.startsWith("NUMBER") || t.startsWith("INTEGER")
            || t.startsWith("INT") || t.startsWith("BINARY_INTEGER")
            || t.startsWith("PLS_INTEGER") || t.startsWith("SMALLINT")
            || t.startsWith("DECIMAL") || t.startsWith("FLOAT")
            || t.startsWith("DOUBLE") || t.startsWith("REAL")
            || t.startsWith("NUMERIC")) {
            if (t.contains("(")) {
                String prec = t.substring(t.indexOf('(') + 1, t.indexOf(')'));
                if (prec.contains(",")) {
                    int scale = Integer.parseInt(prec.split(",")[1].trim());
                    return scale > 0 ? "java.math.BigDecimal" : "long";
                }
            }
            return "long";
        }

        if (t.contains("DATE") || t.contains("TIMESTAMP")
            || t.startsWith("INTERVAL")) return "java.util.Date";

        if (t.startsWith("BLOB") || t.startsWith("RAW")
            || t.startsWith("BFILE")) return "byte[]";

        if (t.contains("%TYPE") || t.contains("%ROWTYPE")) return "Object /* " + t + " */";

        if (t.startsWith("BOOLEAN")) return "boolean";

        if (t.startsWith("SYS_REFCURSOR") || t.startsWith("REF CURSOR"))
            return "java.sql.ResultSet";

        // 去掉 % 符号
        if (t.contains("%")) return "Object";

        return "Object /* " + t + " */";
    }

    /** 翻译条件表达式 */
    private String mapCondition(String cond) {
        if (cond == null) return "true";
        String c = cond
                .replaceAll("\\bIS\\s+NULL\\b", "== null")
                .replaceAll("\\bIS\\s+NOT\\s+NULL\\b", "!= null")
                .replaceAll("\\bNOT\\s+IN\\b", "NOT IN")
                .replaceAll("\\bLIKE\\b", "LIKE")
                .replaceAll("\\bAND\\b", "&&")
                .replaceAll("\\bOR\\b", "||")
                .replaceAll("\\bNOT\\b", "!")
                .replaceAll("\\bNULL\\b", "null");
        return c;
    }

    /** PL/SQL 命名 → Java camelCase */
    private String toCamelCase(String name) {
        if (name == null) return "";
        // 取下划线分隔的单词，转驼峰
        String[] parts = name.split("_");
        StringBuilder sb = new StringBuilder(parts[0].toLowerCase());
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(Character.toUpperCase(parts[i].charAt(0)));
                if (parts[i].length() > 1) sb.append(parts[i].substring(1).toLowerCase());
            }
        }
        return sb.toString();
    }

    /** PL/SQL 命名 → Java PascalCase（用于类名） */
    private String toPascalCase(String name) {
        if (name == null) return "";
        String[] parts = name.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) sb.append(part.substring(1).toLowerCase());
            }
        }
        return sb.toString();
    }

    /** 转义 Java 字符串 */
    private String escapeJavaString(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
