import os
import re
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
JAVA = os.environ.get("JAVA", "java")
ANTLR_JAR = ROOT / "lib" / "antlr-4.9.3-complete.jar"
TRANSLATOR_JAR = ROOT / "target" / "plsql-translator.jar"
MAIN_CLASS = "com.plsql.translator.Main"


def run_flowchart(sql):
    if not TRANSLATOR_JAR.exists():
        raise AssertionError(f"Missing built jar: {TRANSLATOR_JAR}")

    with tempfile.TemporaryDirectory() as tmp:
        tmp_dir = Path(tmp)
        input_path = tmp_dir / "input.sql"
        output_path = tmp_dir / "output.flow.dot"
        input_path.write_text(textwrap.dedent(sql).strip() + "\n", encoding="utf-8")

        cp = os.pathsep.join([str(ANTLR_JAR), str(TRANSLATOR_JAR)])
        subprocess.run(
            [JAVA, "-cp", cp, MAIN_CLASS, "--mode", "flow-graphviz", str(input_path), str(output_path)],
            check=True,
            cwd=str(ROOT),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        return output_path.read_text(encoding="utf-8")


def outgoing_labels(dot):
    labels_by_source = {}
    edge_re = re.compile(r"^\s*(n\d+):[nsew]\s*->\s*(n\d+):[nsew](?:\s*\[(.*?)\])?;")
    label_re = re.compile(r'(?:x?label)="([^"]*)"')
    for line in dot.splitlines():
        match = edge_re.search(line)
        if not match:
            continue
        source = match.group(1)
        attrs = match.group(3) or ""
        label_match = label_re.search(attrs)
        if label_match:
            labels_by_source.setdefault(source, []).append(label_match.group(1))
    return labels_by_source


class FlowchartDotTests(unittest.TestCase):
    def test_if_elsif_false_path_does_not_create_duplicate_no_edges(self):
        dot = run_flowchart(
            """
            CREATE OR REPLACE PROCEDURE branch_demo IS
                v_state NUMBER;
                v_result NUMBER;
            BEGIN
                IF v_state = 1 THEN
                    v_result := 10;
                ELSIF v_state = 2 THEN
                    v_result := 20;
                END IF;

                v_result := v_result + 1;
            END branch_demo;
            /
            """
        )

        duplicate_no_sources = {
            source: labels
            for source, labels in outgoing_labels(dot).items()
            if labels.count("No") > 1
        }
        self.assertEqual({}, duplicate_no_sources)

    def test_consecutive_basic_statements_are_collapsed(self):
        dot = run_flowchart(
            """
            CREATE OR REPLACE PROCEDURE basic_block_demo IS
                v_a NUMBER;
                v_b NUMBER;
                v_c NUMBER;
            BEGIN
                v_a := 1;
                v_b := 2;
                v_c := 3;
            END basic_block_demo;
            /
            """
        )

        self.assertIn("3 statements", dot)


if __name__ == "__main__":
    unittest.main()
