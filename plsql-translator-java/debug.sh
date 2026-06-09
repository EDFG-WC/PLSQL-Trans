#!/bin/zsh
cd "$(dirname "$0")"

echo "=== 1) 类文件验证 ==="
javap -cp "target/classes" com.plsql.translator.PlsqlDrawioGenerator 2>&1 | head -8

echo ""
echo "=== 2) Main (classpath 验证) ==="
java -cp "lib/antlr-4.9.3-complete.jar:target/classes" \
  com.plsql.translator.Main \
  examples/test_procedure.sql 2>&1 | head -3

echo ""
echo "=== 3) DrawioGenerator 详细日志 ==="
java -verbose:class -cp "lib/antlr-4.9.3-complete.jar:target/classes" \
  com.plsql.translator.PlsqlDrawioGenerator \
  examples/test_procedure.sql \
  /tmp/plsql_tree.drawio 2>&1 | tail -20

echo ""
echo "=== 4) 字节码版本 ==="
javap -verbose -cp "target/classes" com.plsql.translator.PlsqlDrawioGenerator 2>&1 | grep "major"
