"""测试 PL/SQL 分类逻辑的完整性。"""
import sys
sys.path.insert(0, "/Users/coder_alex/workspace/PLSQL-Trans/plsql_crawler")

from plsql_crawler.items import (
    CATEGORY_PROCEDURE,
    CATEGORY_FUNCTION,
    CATEGORY_PACKAGE,
    CATEGORY_PACKAGE_BODY,
    CATEGORY_TRIGGER,
    CATEGORY_TYPE,
    CATEGORY_TYPE_BODY,
    CATEGORY_LIBRARY,
    CATEGORY_VIEW,
    CATEGORY_ANONYMOUS,
    CATEGORY_OTHER,
    classify_file,
)

# ── 测试用例 ──────────────────────────────────────────────────
test_cases = [
    # (文件名, 内容片段, 预期分类)
    ("my_proc.prc", """
CREATE OR REPLACE PROCEDURE update_salary (
    p_id IN NUMBER
) IS
BEGIN
    UPDATE emp SET salary = 100 WHERE id = p_id;
END;
""", CATEGORY_PROCEDURE),
    ("my_func.fnc", """
CREATE OR REPLACE FUNCTION get_name (
    p_id IN NUMBER
) RETURN VARCHAR2 IS
    v_name VARCHAR2(100);
BEGIN
    SELECT name INTO v_name FROM emp WHERE id = p_id;
    RETURN v_name;
END;
""", CATEGORY_FUNCTION),
    ("my_pkg.pks", """
CREATE OR REPLACE PACKAGE emp_pkg AS
    PROCEDURE hire(p_name VARCHAR2);
    FUNCTION count RETURN NUMBER;
END emp_pkg;
""", CATEGORY_PACKAGE),
    ("my_pkg.pkb", """
CREATE OR REPLACE PACKAGE BODY emp_pkg AS
    PROCEDURE hire(p_name VARCHAR2) IS
    BEGIN
        INSERT INTO emp(name) VALUES(p_name);
    END;
    FUNCTION count RETURN NUMBER IS
        v NUMBER;
    BEGIN
        SELECT COUNT(*) INTO v FROM emp;
        RETURN v;
    END;
END emp_pkg;
""", CATEGORY_PACKAGE_BODY),
    ("my_trg.trg", """
CREATE OR REPLACE TRIGGER audit_emp
    BEFORE UPDATE ON employees
    FOR EACH ROW
BEGIN
    INSERT INTO audit VALUES(:OLD.id, :NEW.id);
END;
""", CATEGORY_TRIGGER),
    ("my_type.typ", """
CREATE OR REPLACE TYPE person_obj AS OBJECT (
    id NUMBER,
    name VARCHAR2(100),
    MEMBER FUNCTION display RETURN VARCHAR2
);
""", CATEGORY_TYPE),
    ("my_type.tpb", """
CREATE OR REPLACE TYPE BODY person_obj AS
    MEMBER FUNCTION display RETURN VARCHAR2 IS
    BEGIN
        RETURN name;
    END;
END;
""", CATEGORY_TYPE_BODY),
    ("my_lib.sql", """
CREATE OR REPLACE LIBRARY my_lib IS '/usr/lib/lib.so';
""", CATEGORY_LIBRARY),
    ("my_view.sql", """
CREATE OR REPLACE VIEW emp_view AS
    SELECT id, name FROM employees WHERE status = 'ACTIVE';
""", CATEGORY_VIEW),
    ("anon_block.sql", """
DECLARE
    v_name VARCHAR2(100);
BEGIN
    SELECT 'Hello' INTO v_name FROM dual;
    DBMS_OUTPUT.PUT_LINE(v_name);
END;
/
""", CATEGORY_ANONYMOUS),
    ("plain_sql.sql", """
SELECT * FROM employees WHERE department_id = 10;
""", CATEGORY_OTHER),
    ("empty.other", "", CATEGORY_OTHER),
]

print("=" * 60)
print("  PL/SQL 分类测试")
print("=" * 60)

passed = 0
failed = 0

for file_name, content, expected in test_cases:
    cat, method, conf = classify_file(file_name, content)
    status = "✓" if cat == expected else "✗"
    if cat == expected:
        passed += 1
        print(f"  {status} {file_name:20s} → {cat:20s} (conf={conf:.2f}, method={method})")
    else:
        failed += 1
        print(f"  {status} {file_name:20s} → got={cat:20s} expected={expected:20s}")

print(f"\n  通过: {passed}  失败: {failed}  总计: {len(test_cases)}")
print(f"  {'所有测试通过!' if failed == 0 else '有失败测试!'}")
print("=" * 60)
