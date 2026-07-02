# Oracle SQL authoring rules for mcp-oracle-db

Grounded in the [Oracle Database SQL Language Reference, 19c]
(https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/).
These rules prevent the parse errors an LLM most often produces when writing
Oracle SQL (`ORA-00923`, `ORA-00936`, `ORA-00904`, `ORA-00911`, `ORA-00933`…).
They are bundled inside the server and exposed via the `oracle-sql-style` prompt
and the server `instructions`.

## 1. One statement per call

Send **one** SQL statement per `run_query` / `execute_dml` call. Do **not** add a
trailing semicolon `;`, do not chain statements with `;`, and do not send
anonymous PL/SQL blocks. A trailing `;` raises `ORA-00911: invalid character`.

## 2. Identifiers — double quotes only

- Oracle folds **unquoted** identifiers to **UPPERCASE**. `select Name from emp`
  resolves to column `NAME`. If the real column is mixed-case, you **must**
  double-quote it: `select "Name" from emp`.
- Quote with **double quotes** `"..."` for identifiers, **single quotes**
  `'...'` for string literals. Backticks `` ` `` (MySQL/Postgres) and brackets
  `[ ]` (SQL Server) are **invalid** in Oracle and raise `ORA-00911`.
- Always qualify with the schema: `"HR"."EMPLOYEES"`, not bare `EMPLOYEES`.
- Prefer the server's identifier helpers when building fixed-shape SQL
  (`SqlIdentifiers` validates and double-quotes).

## 3. Aliases — use AS, avoid reserved words

- Alias columns with `AS`: `salary AS pay`. Without `AS`, a reserved word as the
  alias is the #1 cause of `ORA-00923: FROM keyword not found where expected`
  (e.g. `SELECT hire_date date FROM emp` — `DATE` is reserved).
- Never use these as a bare alias — quote them or pick another name:
  `DATE, NUMBER, LEVEL, SIZE, COMMENT, USER, ROWID, ROWNUM, ORDER, GROUP,
  SIZE, MODE, START, COUNT, UID, SYSDATE, CURSOR, DESC, ASC`.
- Table aliases: `FROM employees e, departments d` is fine.

## 4. SELECT-list commas

- Separate every selected expression with a comma. A missing comma makes Oracle
  read the next column as an alias and trips `ORA-00923` / `ORA-00936`.
- No trailing comma before `FROM`.

## 5. Literals

- Strings: single quotes `'hello'`. Escape a literal quote by doubling it:
  `'O''Brien'`. An unterminated literal raises `ORA-01756`.
- Dates: use a literal `DATE '2024-01-31'` or `TO_DATE('31/01/2024','DD/MM/YYYY')`.
  Never compare a DATE column to a bare string without conversion.
- Numbers: unquoted (`100`, `3.14`). Booleans do not exist in Oracle SQL — use
  `1/0`, `'Y'/'N'`, or a `CHECK` column.

## 6. Pagination — FETCH FIRST, never LIMIT

- Oracle's row-limiting clause is
  `OFFSET n ROWS FETCH NEXT m ROWS ONLY` (or `FETCH FIRST n ROWS ONLY`).
- `LIMIT n` is MySQL/Postgres and is a **syntax error** in Oracle
  (`ORA-00933: SQL command not properly ended`).
- `run_query` accepts `offset`/`limit` parameters and wraps the statement for
  you — prefer those over hand-writing pagination, and include an `ORDER BY`
  for stable pages.

## 7. Set operations and parentheses

- Balance every `(`. `ORA-00907: missing right parenthesis` usually means a
  missing closing paren in a function call or subquery.
- In `UNION`/`UNION ALL`/`MINUS`/`INTERSECT`, every branch must return the
  same number and type of columns, otherwise `ORA-01790`.

## 8. Things Oracle does NOT have

- No `LIMIT`, no `ILIKE`, no `RETURNING ... INTO` in plain SELECT, no
  `SELECT ... INTO` outside PL/SQL, no `GROUP_CONCAT` (use `LISTAGG`), no
  `IF`/`IIF` in SQL (use `CASE WHEN`), no `GETDATE()` (use `SYSDATE` /
  `CURRENT_DATE`), no `CONCAT(a,b,c)` variadic in old versions (use `a || b`).
- For top-N: `ORDER BY ... FETCH FIRST n ROWS ONLY`.
- For string concatenation: the `||` operator.

## 9. Recursion / CTEs

`WITH` (CTE) and `CONNECT BY` may be rejected by the server's parser path on
`run_query`. For set differences use `MINUS`; for row-number/gap problems prefer
returning the rows and computing on the client side.

## 10. Names and case in results

Result column keys come back in **Oracle's case** (usually UPPERCASE). When you
read `row["NAME"]`, expect uppercase unless the column was created mixed-case
and quoted. Call `describe_table` first to confirm exact names, types and
nullability before writing SELECT/INSERT/UPDATE against an unverified table.

## 11. Privileges — the error is authoritative

If Oracle returns `ORA-01031: insufficient privileges` for any
`CREATE/ALTER/DROP/GRANT/...`, that is **by design** — the server connects as a
least-privilege user with no DDL/DCL. Do not retry; have a DBA run the change.
`ORA-00942: table or view does not exist` often means the user has no `SELECT`
grant on that object (or the name/schema is wrong) — confirm with
`list_tables` / `describe_table`.

## Quick troubleshooting table

| Error | Likely cause | Fix |
|---|---|---|
| ORA-00923 | reserved-word alias w/o AS, missing comma, bad identifier | use `AS`/`"alias"`; comma-check; double-quote identifiers |
| ORA-00936 | empty select list, dangling comma, missing operand | complete each expression; remove trailing comma |
| ORA-00904 | misspelled/non-existent column | `describe_table`; mind UPPERCASE folding |
| ORA-00911 | trailing `;`, backtick/bracket quoting | one statement, no `;`, double quotes only |
| ORA-00933 | extra text after statement, `LIMIT` | remove trailing text; use `FETCH FIRST` |
| ORA-00907 | unbalanced `(` | match parentheses |
| ORA-01756 | unterminated `'...'` | close the literal; escape `''` |
| ORA-01790 | mismatched set-op branches | align column count/types |
| ORA-00942 | wrong name or no grant | `list_tables`; check schema & grants |
| ORA-01031 | DDL/DCL blocked by design | run as DBA; do not widen the MCP user |
