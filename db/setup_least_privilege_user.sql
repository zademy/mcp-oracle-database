-- =============================================================================
-- mcp-oracle-db: least-privilege Oracle user setup
-- =============================================================================
-- Run this as a DBA (SYSTEM or a user with the necessary grants). It creates a
-- dedicated Oracle user for the MCP server that:
--   * can CONNECT
--   * can READ the data dictionary (SELECT_CATALOG_ROLE) -> ALL_* views,
--     DBMS_METADATA, DBMS_XPLAN, v$version ...
--   * can run SELECT / INSERT / UPDATE / DELETE / MERGE on existing tables in the
--     target schema(s), and SELECT on their views and sequences
--   * CANNOT create, alter, drop, truncate, rename, grant or revoke anything
--     (no DDL/DCL grants, no tablespace quota)
--
-- This is defence in depth: even if the application-level SqlGuard were bypassed,
-- the database user lacks the privileges to change database structure.
--
-- >>> EDIT THE <...> PLACEHOLDERS BELOW BEFORE RUNNING <<<
-- =============================================================================

-- 1. Create the user. Use a strong password (preferably supplied via a secret).
CREATE USER &mcp_user IDENTIFIED BY "&mcp_password"
  DEFAULT TABLESPACE users
  TEMPORARY TABLESPACE temp
  ACCOUNT LOCK;

-- 2. Allow connections and read-only dictionary access.
GRANT CREATE SESSION TO &mcp_user;
GRANT SELECT_CATALOG_ROLE TO &mcp_user;

-- 3. Per-object grants for each schema the server must work with.
--    Replace &target_schema with the schema(s) you want to expose.
BEGIN
  FOR r IN (SELECT table_name FROM dba_tables WHERE owner = '&target_schema') LOOP
    EXECUTE IMMEDIATE
      'GRANT SELECT, INSERT, UPDATE, DELETE ON &target_schema."' || r.table_name || '" TO &mcp_user';
  END LOOP;

  FOR r IN (SELECT view_name FROM dba_views WHERE owner = '&target_schema') LOOP
    EXECUTE IMMEDIATE
      'GRANT SELECT ON &target_schema."' || r.view_name || '" TO &mcp_user';
  END LOOP;

  FOR r IN (SELECT sequence_name FROM dba_sequences WHERE owner = '&target_schema') LOOP
    EXECUTE IMMEDIATE
      'GRANT SELECT ON &target_schema."' || r.sequence_name || '" TO &mcp_user';
  END LOOP;
END;
/

-- 4. (Optional) private synonyms so objects are addressable without the schema
--    prefix. Uncomment and repeat per schema if desired.
-- BEGIN
--   FOR r IN (SELECT object_name FROM all_objects
--             WHERE owner = '&target_schema'
--               AND object_type IN ('TABLE','VIEW','SEQUENCE','PROCEDURE','FUNCTION','PACKAGE')) LOOP
--     EXECUTE IMMEDIATE 'CREATE SYNONYM &mcp_user."' || r.object_name
--                    || '" FOR &target_schema."' || r.object_name || '"';
--   END LOOP;
-- END;
/

-- 5. (Optional) explain_plan tool writes to PLAN_TABLE. If a shared PLAN_TABLE
--    synonym exists, grant write access. Skip if you do not use explain_plan.
-- GRANT SELECT, INSERT, DELETE ON sys.plan_table$ TO &mcp_user;

-- 6. DDL grants intentionally omitted.
--    SqlGuard blocks all CREATE/ALTER/DROP/TRUNCATE/RENAME-style structural
--    operations, and create_* MCP tools are not exposed. COMMENT tools only work
--    on objects the user owns; for tables in &target_schema they return ORA-01031
--    unless &target_schema == &mcp_user.
--    Do not grant CREATE VIEW/SYNONYM/SEQUENCE, CREATE ANY INDEX, CREATE TABLE,
--    CREATE PUBLIC SYNONYM, or tablespace quota unless you deliberately fork the
--    project policy and add new tests/docs for that broader trust model.
-- NOTE: avoid GRANT UNLIMITED TABLESPACE — it grants quota on every non-SYSTEM
--       tablespace in the database, including any the DBA adds later.

-- 7. Enable the account once the password is securely distributed.
ALTER USER &mcp_user ACCOUNT UNLOCK;

-- 8. (Optional) SQL Tuning Advisor. Enable ONLY if you intend to expose the
--    run_sql_tuning_advisor tool, which drives DBMS_SQLTUNE. All *_TUNING_TASK
--    interfaces require the ADVISOR privilege. This is a powerful system
--    privilege (it can create/drop advisor tasks and consume optimizer effort),
--    so it is deliberately NOT granted by default. Without it, the tool returns
--    a clear guidance message instead of failing.
-- GRANT ADVISOR TO &mcp_user;

-- 9. (Optional) PL/SQL execution via the call_procedure tool.
--    The tool drives JDBC CallableStatements, which require EXECUTE on each
--    target object. Grant EXECUTE à la carte only on the specific procedures,
--    functions and packages the MCP client must run. Do NOT grant broad system
--    privileges (e.g. EXECUTE ANY PROCEDURE) — keep the least-privilege model.
--    Replace &target_schema and the object names with the ones you want to expose.
--
--    Example:
-- GRANT EXECUTE ON &target_schema."MY_PACKAGE"          TO &mcp_user;
-- GRANT EXECUTE ON &target_schema."ADD_ORDER"           TO &mcp_user;
-- GRANT EXECUTE ON &target_schema."GET_CUSTOMER_CURSOR" TO &mcp_user;
--
--    To grant EXECUTE on every procedure/function/package in a schema at once
--    (broader trust — review before using):
-- BEGIN
--   FOR r IN (SELECT object_name FROM all_objects
--             WHERE owner = '&target_schema'
--               AND object_type IN ('PROCEDURE','FUNCTION','PACKAGE')) LOOP
--     EXECUTE IMMEDIATE
--       'GRANT EXECUTE ON &target_schema."' || r.object_name || '" TO &mcp_user';
--   END LOOP;
-- END;
-- /

-- NOTE: intentionally NO quota grants and NO CREATE/ALTER/DROP privileges by
--       default. The MCP user remains unable to change structure unless you
--       explicitly uncomment section 6 above.
