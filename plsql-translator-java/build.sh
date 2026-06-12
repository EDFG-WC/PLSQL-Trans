#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ANTLR_JAR="$SCRIPT_DIR/lib/antlr-4.9.3-complete.jar"
SRC_ANTLR="$SCRIPT_DIR/src/main/antlr"
SRC_JAVA="$SCRIPT_DIR/src/main/java"
GEN_DIR="$SCRIPT_DIR/src/generated/java"
BUILD_DIR="$SCRIPT_DIR/target"
PARSER_PKG="com/plsql/translator/parser"
OUTPUT_JAR="$BUILD_DIR/plsql-translator.jar"

JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAR="${JAVA_HOME:+$JAVA_HOME/bin/}jar"

echo "=== PL/SQL → Java 翻译器 构建 ==="

# 1) 生成 Java 解析器代码
echo "[1/4] 生成解析器代码..."
rm -rf "$GEN_DIR"
mkdir -p "$GEN_DIR/$PARSER_PKG"
$JAVA -jar "$ANTLR_JAR" \
    -o "$GEN_DIR/$PARSER_PKG" \
    -package com.plsql.translator.parser \
    -no-listener \
    -visitor \
    "$SRC_ANTLR/PlSqlLexer.g4" \
    "$SRC_ANTLR/PlSqlParser.g4"

# 2) 复制基类到生成目录，并修正包声明
echo "[2/4] 修正基类包声明..."
for BASE in PlSqlLexerBase PlSqlParserBase; do
    sed "s%///{packageLine}%package com.plsql.translator.parser;%" \
        "$SRC_ANTLR/$BASE.java" > "$GEN_DIR/$PARSER_PKG/$BASE.java"
done

# 3) 编译
echo "[3/4] 编译 Java 源码..."
mkdir -p "$BUILD_DIR/classes"
$JAVAC -cp "$ANTLR_JAR" \
    -encoding UTF-8 \
    -d "$BUILD_DIR/classes" \
    -sourcepath "$GEN_DIR" \
    "$GEN_DIR/$PARSER_PKG/"*.java

$JAVAC -cp "$ANTLR_JAR:$BUILD_DIR/classes" \
    -encoding UTF-8 \
    -d "$BUILD_DIR/classes" \
    -sourcepath "$SRC_JAVA" \
    "$SRC_JAVA/com/plsql/translator/"*.java

# 4) 打包
echo "[4/4] 打包 JAR..."
cd "$BUILD_DIR/classes"
echo "Main-Class: com.plsql.translator.Main" > "$BUILD_DIR/manifest.txt"
$JAR cfm "$OUTPUT_JAR" "$BUILD_DIR/manifest.txt" .

# 创建运行脚本
cat > "$SCRIPT_DIR/translate.sh" << 'RUN'
#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
java -cp "$SCRIPT_DIR/lib/antlr-4.9.3-complete.jar:$SCRIPT_DIR/target/plsql-translator.jar" \
    com.plsql.translator.Main "$@"
RUN
chmod +x "$SCRIPT_DIR/translate.sh"

ANTLR_SIZE=$(du -h "$ANTLR_JAR" | cut -f1)
JAR_SIZE=$(du -h "$OUTPUT_JAR" 2>/dev/null | cut -f1 || echo "?")
echo ""
echo "=== 构建完成 ==="
echo "  ANTLR jar:  $ANTLR_SIZE"
echo "  输出 jar:   $JAR_SIZE"
echo "  运行:       ./translate.sh <input.sql> [output.java]"
echo "  Pipeline:   echo 'PL/SQL...' | ./translate.sh -"
