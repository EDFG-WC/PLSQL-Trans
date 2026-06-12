#!/bin/zsh
cd "$(dirname "$0")"

echo "=== 1) 类文件验证 ==="
javap -cp "target/classes" com.plsql.translator.PlsqlGraphvizGenerator 2>&1 | head -8

echo ""
echo "=== 2) Main (classpath 验证) ==="
java -cp "lib/antlr-4.9.3-complete.jar:target/classes" \
  com.plsql.translator.Main \
  examples/test_procedure.sql 2>&1 | head -3

echo ""
echo "=== 3) GraphvizGenerator 详细日志 ==="
java -verbose:class -cp "lib/antlr-4.9.3-complete.jar:target/classes" \
  com.plsql.translator.PlsqlGraphvizGenerator \
  examples/test_procedure.sql \
  /tmp/plsql_tree.dot 2>&1 | tail -20

echo ""
echo "=== 4) Flowchart Graphviz 测试 ==="
java -cp "lib/antlr-4.9.3-complete.jar:target/classes" \
  com.plsql.translator.PlsqlFlowchartToGraphvizGenerator \
  examples/test_procedure.sql \
  /tmp/plsql_flow.dot 2>&1

echo ""
echo "=== 5) 检查生成的 DOT 文件 ==="
head -20 /tmp/plsql_tree.dot 2>/dev/null || echo "(no tree dot)"
echo "---"
head -20 /tmp/plsql_flow.dot 2>/dev/null || echo "(no flow dot)"
