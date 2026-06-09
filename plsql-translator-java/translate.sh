#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
java -cp "$SCRIPT_DIR/lib/antlr-4.9.3-complete.jar:$SCRIPT_DIR/target/plsql-translator.jar" \
    com.plsql.translator.Main "$@"
