package com.plsql.translator;

import java.io.*;
import java.nio.file.*;

/**
 * PL/SQL 工具 CLI — 统一入口。
 *
 * 支持三种输出模式:
 *   --mode java    将 PL/SQL 翻译为 Java 代码（默认）
 *   --mode json    输出 JSON AST
 *   --mode drawio  输出 draw.io 图文件
 *
 * 用法:
 *   translate.sh input.sql                  → Java 到 stdout
 *   translate.sh --mode drawio input.sql    → 生成 .drawio 文件
 *   translate.sh --mode json input.sql      → 生成 .json 文件
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
            System.err.println("用法: ... [--mode java|json|drawio] <input.sql> [output]");
            System.err.println("       echo 'code' | ... [--mode java|json|drawio|flowchart] - [output]");
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
            case "drawio":
                handleDrawio(source, args, fileArg, inputPath);
                break;
            case "flowchart":
                handleFlowchart(source, args, fileArg, inputPath);
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

    static void handleDrawio(String source, String[] args, int fileArg, String inputPath) throws Exception {
        String drawio = PlSqlDrawioGenerator.plsqlToDrawio(source);
        String out = (args.length > fileArg + 1) ? args[fileArg + 1]
                    : inputPath.equals("-") ? "output.drawio"
                    : inputPath.replace(".sql", ".drawio");
        Files.write(Paths.get(out), drawio.getBytes());
        System.out.println("✓ " + out);
    }

    static void handleFlowchart(String source, String[] args, int fileArg, String inputPath) throws Exception {
        String drawio = PlSqlFlowchartGenerator.plsqlToFlowchart(source);
        String out = (args.length > fileArg + 1) ? args[fileArg + 1]
                    : inputPath.equals("-") ? "output.flow.drawio"
                    : inputPath.replace(".sql", ".flow.drawio");
        Files.write(Paths.get(out), drawio.getBytes());
        System.out.println("OK: " + out);
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
