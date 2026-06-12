#!/bin/zsh
cd "$(dirname "$0")"
JAR="plsql-translator-java/target/plsql-translator.jar"
ANTLR="plsql-translator-java/lib/antlr-4.9.3-complete.jar"
BASE="data"
OUT="output/top5"

# 5 个最复杂的文件
FILES=(
  "PACKAGE_BODY/jabautista_GeniisysSCA/jabautista_GeniisysSCA__src__main__resources__sql__packages__giuw_pol_dist_final_pkg.pkb:giuw_pol_dist_final_pkg"
  "PROCEDURE/jabautista_GeniisysSCA/jabautista_GeniisysSCA__src__main__resources__sql__procedures__DEFERRED_EXTRACT3_DTL.prc:DEFERRED_EXTRACT3_DTL"
  "PACKAGE_BODY/jabautista_GeniisysSCA/jabautista_GeniisysSCA__src__main__resources__sql__packages__giuts009_pkg.pkb:giuts009_pkg"
  "PACKAGE/jabautista_GeniisysSCA/jabautista_GeniisysSCA__src__main__resources__sql__packages__giuw_pol_dist_final_pkg.pks:giuw_pol_dist_final_pkg_spec"
  "PACKAGE_BODY/jabautista_GeniisysSCA/jabautista_GeniisysSCA__src__main__resources__sql__packages__p_uwreports.pkb:p_uwreports"
)

mkdir -p "$OUT/java" "$OUT/graphviz" "$OUT/flow-graphviz"

for entry in "${FILES[@]}"; do
  path="${entry%%:*}"
  name="${entry##*:}"
  src="$BASE/$path"

  # 检查源文件
  if [ ! -f "$src" ]; then
    echo "✗ $name: 源文件不存在 $src"
    continue
  fi
  size=$(wc -c < "$src")
  echo "→ $name ($(echo $size | xargs) bytes)"

  # Java 翻译
  echo -n "  Java: "
  java -cp "$ANTLR:$JAR" com.plsql.translator.Main --mode java \
    "$src" "$OUT/java/${name}.java" 2>&1 | tail -1

  # Graphviz 语法树图
  echo -n "  Graphviz: "
  java -cp "$ANTLR:$JAR" com.plsql.translator.Main --mode graphviz \
    "$src" "$OUT/graphviz/${name}.dot" 2>&1 | tail -1

  # Graphviz 控制流图
  echo -n "  Flow-Graphviz: "
  java -cp "$ANTLR:$JAR" com.plsql.translator.Main --mode flow-graphviz \
    "$src" "$OUT/flow-graphviz/${name}.flow.dot" 2>&1 | tail -1
done

echo ""
echo "=== 产出 ==="
ls -lhS "$OUT/java/" "$OUT/graphviz/" "$OUT/flow-graphviz/"
