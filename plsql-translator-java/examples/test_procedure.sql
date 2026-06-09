CREATE OR REPLACE PROCEDURE update_employee_salary (
    p_emp_id  IN  NUMBER,
    p_raise   IN  NUMBER
) IS
    v_current_salary NUMBER;
BEGIN
    SELECT salary INTO v_current_salary
    FROM employees
    WHERE employee_id = p_emp_id;

    IF v_current_salary IS NULL THEN
        v_current_salary := 0;
    END IF;

    UPDATE employees
    SET salary = v_current_salary + p_raise
    WHERE employee_id = p_emp_id;

    COMMIT;
EXCEPTION
    WHEN NO_DATA_FOUND THEN
        DBMS_OUTPUT.PUT_LINE('Employee not found');
    WHEN OTHERS THEN
        DBMS_OUTPUT.PUT_LINE('Error: ' || SQLERRM);
        ROLLBACK;
END update_employee_salary;
/
