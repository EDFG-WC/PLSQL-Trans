package com.plsql.translator;

import java.io.*;
import java.nio.file.*;

/**
 * PL/SQL 工具 CLI — 统一入口。
 *
 * 支持四种输出模式:
 *   --mode java         将 PL/SQL 翻译为 Java 代码（默认）
 *   --mode json         输出 JSON AST
 *   --mode graphviz      输出 Graphviz DOT 语法树图
 *   --mode flow-graphviz 输出 Graphviz DOT 控制流图
 *   --mode call-graph    输出 Graphviz DOT 调用关系图
 *
 * 用法:
 *   translate.sh input.sql                     → Java 到 stdout
 *   translate.sh --mode graphviz input.sql     → 生成 .dot 文件
 *   translate.sh --mode json input.sql         → 生成 .json 文件
 *   translate.sh --mode call-graph input.sql   → 生成 .call.dot 文件
 */
public class Main {

    public static void main(String[] args) throws Exception {
        String mode = "java";
        int fileArg = 0;

        if (args.length >= 2 && "--mode".equals(args[0])) {
            mode = args[1].toLowerCase();
            fileArg = 2;
        }

        if (fileArg >= args.length) {
            System.err.println("用法: ... [--mode java|json|graphviz|flow-graphviz|call-graph] <input.sql> [output]");
            System.err.println("       echo 'code' | ... [--mode java|json|graphviz|flow-graphviz|call-graph] - [output]");
            System.exit(1);
        }

        String inputPath = args[fileArg];
        String source;
        if ("-".equals(inputPath)) {
            BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
            StringBuilder sb = new StringBuilder();
            String line; while ((line = r.readLine()) != null) sb.append(line).append("\n");
            source = sb.toString();
        } else {
            source = new String(Files.readAllBytes(Paths.get(inputPath)));
        }

        switch (mode) {
            case "json":
                handleJson(source, args, fileArg, inputPath);
                break;
            case "graphviz":
                handleGraphviz(source, args, fileArg, inputPath);
                break;
            case "flow-graphviz":
                handleFlowGraphviz(source, args, fileArg, inputPath);
                break;
            case "call-graph":
                handleCallGraph(source, args, fileArg, inputPath);
                break;
            default:
                handleJava(source, args, fileArg, inputPath);
                break;
        }
    }

    static void handleJson(String source, String[] args, int fileArg, String inputPath) throws Exception {
        String json = PlSqlAstToJson.plsqlToJson(source);
        String out = (args.length > fileArg + 1) ? args[fileArg + 1]
                    : inputPath.equals("-") ? "output.json"
                    : inputPath.replace(".sql", ".json");
        Files.write(Paths.get(out), json.getBytes());
        System.out.println("✓ " + out);
    }

    static void handleGraphviz(String source, String[] args, int fileArg, String inputPath) throws Exception {
        String dot = PlSqlGraphvizGenerator.plsqlToGraphviz(source);
        String out = (args.length > fileArg + 1) ? args[fileArg + 1]
                    : inputPath.equals("-") ? "output.dot"
                    : inputPath.replace(".sql", ".dot");
        Files.write(Paths.get(out), dot.getBytes());
        System.out.println("✓ " + out);
    }

    static void handleFlowGraphviz(String source, String[] args, int fileArg, String inputPath) throws Exception {
        String dot = PlSqlFlowchartToGraphvizGenerator.plsqlToFlowchart(source);
        String out = (args.length > fileArg + 1) ? args[fileArg + 1]
                    : inputPath.equals("-") ? "output.flow.dot"
                    : inputPath.replace(".sql", ".flow.dot");
        Files.write(Paths.get(out), dot.getBytes());
        System.out.println("OK: " + out);
    }

    static void handleCallGraph(String source, String[] args, int fileArg, String inputPath) throws Exception {
        String dot = PlSqlCallGraphGenerator.plsqlToCallGraph(source);
        String out = (args.length > fileArg + 1) ? args[fileArg + 1]
                    : inputPath.equals("-") ? "output.call.dot"
                    : inputPath.replace(".sql", ".call.dot");
        Files.write(Paths.get(out), dot.getBytes());
        System.out.println("✓ " + out);
    }

    static void handleJava(String source, String[] args, int fileArg, String inputPath) throws Exception {
        PlSqlToJavaTranslator t = new PlSqlToJavaTranslator();
        String code = t.translate(source);
        if (args.length > fileArg + 1) {
            Files.write(Paths.get(args[fileArg + 1]), code.getBytes());
            System.out.println("✓ " + args[fileArg + 1]);
        } else {
            System.out.println(code);
        }
    }
}
