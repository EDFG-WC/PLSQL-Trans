# PL/SQL 存储过程代码分类标准

## 一、定义范围

**存储过程代码（PL/SQL Stored Code）** 广义上指存储在 Oracle 数据库中的 PL/SQL 可执行单元。
狭义上指通过 `CREATE [OR REPLACE]` 语句创建、持久化在数据库中的命名 PL/SQL 对象。
本爬虫项目涵盖以下所有类别，按文件扩展名和代码内关键词双重判定。

---

## 二、分类体系

### 1. PROCEDURE（存储过程）

| 属性 | 说明 |
|------|------|
| 关键词 | `CREATE [OR REPLACE] PROCEDURE` |
| 返回值 | 无（通过 OUT / IN OUT 参数返回） |
| 用途 | 封装业务逻辑、数据操作 |
| 典型扩展名 | `.prc`, `.sql` |

```
CREATE OR REPLACE PROCEDURE update_employee_salary (
    p_emp_id  IN  employees.employee_id%TYPE,
    p_raise   IN  NUMBER
) IS
BEGIN
    UPDATE employees SET salary = salary + p_raise
    WHERE employee_id = p_emp_id;
END update_employee_salary;
/
```

### 2. FUNCTION（函数）

| 属性 | 说明 |
|------|------|
| 关键词 | `CREATE [OR REPLACE] FUNCTION` |
| 返回值 | 必须显式 `RETURN` 一个值 |
| 用途 | 计算、查询、返回标量/集合结果 |
| 典型扩展名 | `.fnc`, `.sql` |

```
CREATE OR REPLACE FUNCTION get_emp_salary (
    p_emp_id IN employees.employee_id%TYPE
) RETURN NUMBER IS
    v_salary employees.salary%TYPE;
BEGIN
    SELECT salary INTO v_salary FROM employees
    WHERE employee_id = p_emp_id;
    RETURN v_salary;
END get_emp_salary;
/
```

### 3. PACKAGE（包规范）

| 属性 | 说明 |
|------|------|
| 关键词 | `CREATE [OR REPLACE] PACKAGE`（**不含** `BODY`） |
| 内容 | 类型声明、变量声明、游标声明、子程序声明（只签名） |
| 用途 | 封装接口，隐藏实现 |
| 典型扩展名 | `.pks`, `.pck`, `.sql` |

```
CREATE OR REPLACE PACKAGE emp_mgmt AS
    PROCEDURE hire_employee(p_name  VARCHAR2, p_salary NUMBER);
    FUNCTION get_employee_count RETURN NUMBER;
END emp_mgmt;
/
```

### 4. PACKAGE BODY（包体）

| 属性 | 说明 |
|------|------|
| 关键词 | `CREATE [OR REPLACE] PACKAGE BODY` |
| 内容 | 包中声明的子程序、游标的完整实现 |
| 用途 | 实现包规范 |
| 典型扩展名 | `.pkb`, `.pck`, `.sql` |

```
CREATE OR REPLACE PACKAGE BODY emp_mgmt AS
    PROCEDURE hire_employee(p_name VARCHAR2, p_salary NUMBER) IS
    BEGIN
        INSERT INTO employees(name, salary) VALUES(p_name, p_salary);
    END hire_employee;

    FUNCTION get_employee_count RETURN NUMBER IS
        v_count NUMBER;
    BEGIN
        SELECT COUNT(*) INTO v_count FROM employees;
        RETURN v_count;
    END get_employee_count;
END emp_mgmt;
/
```

### 5. TRIGGER（触发器）

| 属性 | 说明 |
|------|------|
| 关键词 | `CREATE [OR REPLACE] TRIGGER` |
| 触发事件 | `BEFORE/AFTER/INSTEAD OF` + `INSERT/UPDATE/DELETE/DDL` |
| 用途 | 自动执行数据校验、审计、同步 |
| 典型扩展名 | `.trg`, `.sql` |

```
CREATE OR REPLACE TRIGGER audit_employee_changes
    BEFORE UPDATE ON employees
    FOR EACH ROW
BEGIN
    INSERT INTO audit_log(table_name, old_value, new_value)
    VALUES ('EMPLOYEES', :OLD.salary, :NEW.salary);
END;
/
```

### 6. TYPE（类型规范）

| 属性 | 说明 |
|------|------|
| 关键词 | `CREATE [OR REPLACE] TYPE`（**不含** `BODY`） |
| 内容 | 对象类型定义、集合类型、属性声明、方法签名 |
| 典型扩展名 | `.typ`, `.sql` |

```
CREATE OR REPLACE TYPE employee_obj AS OBJECT (
    emp_id    NUMBER,
    emp_name  VARCHAR2(100),
    MEMBER FUNCTION get_info RETURN VARCHAR2
);
/
```

### 7. TYPE BODY（类型体）

| 属性 | 说明 |
|------|------|
| 关键词 | `CREATE [OR REPLACE] TYPE BODY` |
| 内容 | 类型方法的实现 |
| 典型扩展名 | `.tpb`, `.sql` |

```
CREATE OR REPLACE TYPE BODY employee_obj AS
    MEMBER FUNCTION get_info RETURN VARCHAR2 IS
    BEGIN
        RETURN 'Employee: ' || emp_name || ' (' || emp_id || ')';
    END;
END;
/
```

### 8. LIBRARY（外部库 — 扩展分类）

| 属性 | 说明 |
|------|------|
| 关键词 | `CREATE [OR REPLACE] LIBRARY` |
| 内容 | 调用外部 C / Java 共享库的规范 |
| 典型扩展名 | `.sql`, `.lib` |

### 9. VIEW with PL/SQL（含 PL/SQL 的视图）

| 属性 | 说明 |
|------|------|
| 关键词 | `CREATE [OR REPLACE] VIEW` + 子查询含 PL/SQL 函数 |
| 典型扩展名 | `.vw`, `.sql` |

### 10. Anonymous Block（匿名块）

| 属性 | 说明 |
|------|------|
| 结构 | `DECLARE ... BEGIN ... EXCEPTION ... END;` |
| 用途 | 脚本、测试、一次性任务 |
| 典型扩展名 | `.sql` |

```
DECLARE
    v_name VARCHAR2(100);
BEGIN
    SELECT 'Hello' INTO v_name FROM dual;
    DBMS_OUTPUT.PUT_LINE(v_name);
END;
/
```

---

## 三、PL/SQL 文件扩展名映射表

| 扩展名 | 含义 | 主要分类 |
|--------|------|----------|
| `.pks` | Package Spec | PACKAGE |
| `.pkb` | Package Body | PACKAGE BODY |
| `.pck` | Package (混合) | PACKAGE / PACKAGE BODY |
| `.prc` | Procedure | PROCEDURE |
| `.fnc` | Function | FUNCTION |
| `.trg` | Trigger | TRIGGER |
| `.typ` | Type Spec | TYPE |
| `.tpb` | Type Body | TYPE BODY |
| `.vw`  | View | VIEW |
| `.pls` | PL/SQL Server Page | 需内容分类 |
| `.plb` | PL/SQL Binary | 加密代码 → OTHER |
| `.ddl` | DDL Script | 需内容分类 |
| `.sql` | SQL Script (通用) | 需内容分类 |

---

## 四、分类优先级（文件判定逻辑）

### 第一级：文件扩展名判定
从 `.pks` / `.pkb` / `.pck` / `.prc` / `.fnc` / `.trg` / `.typ` / `.tpb` / `.vw` 可直接映射到
对应的 PL/SQL 类别。

### 第二级：内容关键词判定（用于 `.sql` / `.pls` / `.ddl`）
正则扫描文件前 5000 字符，按出现顺序匹配以下关键词：

| 优先级 | 关键词 | 类别 |
|--------|--------|------|
| 1 | `CREATE[ ]+(OR REPLACE[ ]+)?PROCEDURE` | PROCEDURE |
| 2 | `CREATE[ ]+(OR REPLACE[ ]+)?FUNCTION` | FUNCTION |
| 3 | `CREATE[ ]+(OR REPLACE[ ]+)?PACKAGE[ ]+BODY` | PACKAGE BODY |
| 4 | `CREATE[ ]+(OR REPLACE[ ]+)?PACKAGE` | PACKAGE |
| 5 | `CREATE[ ]+(OR REPLACE[ ]+)?TRIGGER` | TRIGGER |
| 6 | `CREATE[ ]+(OR REPLACE[ ]+)?TYPE[ ]+BODY` | TYPE BODY |
| 7 | `CREATE[ ]+(OR REPLACE[ ]+)?TYPE` | TYPE |
| 8 | `CREATE[ ]+(OR REPLACE[ ]+)?LIBRARY` | LIBRARY |
| 9 | `CREATE[ ]+(OR REPLACE[ ]+)?VIEW` | VIEW |
| 10 | `DECLARE` ~ `BEGIN` ~ `END` 模式 | ANONYMOUS BLOCK |

### 第三级：未匹配 → OTHER（含视图 SQL、DDL、配置等）

---

*最后更新: 2026-06-03*
