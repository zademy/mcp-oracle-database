# Changelog

All notable changes to **mcp-oracle-db** are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

The project has not yet cut a tagged release. The entries below describe the
initial development leading up to the first `0.1.0` tag. When `0.1.0` is cut,
this block moves under `[0.1.0] - YYYY-MM-DD`.

### Added

- **Core server** — Spring Boot 4.1 + Spring AI 2.0 MCP STDIO server exposing
  Oracle Database introspection and SQL execution to MCP clients
  (Claude Desktop, Cursor, …). Java 26 virtual threads. (39e93ce)
- **Security model — defence in depth.**
  - `SqlGuard`: JSqlParser 4.9 classification of every user-supplied SQL
    string before it reaches Oracle. Allow-list (`SELECT`,
    `INSERT`/`UPDATE`/`DELETE`/`MERGE`, `COMMENT ON ...`), a
    leading-keyword regex that blocks structural verbs (`CREATE`, `ALTER`, `DROP`,
    `GRANT`, `REVOKE`, `PURGE`, `FLASHBACK`, `LOCK TABLE`, `AUDIT`,
    `NOAUDIT`, `ANALYZE`) even when the parser cannot model them, and a
    semicolon-outside-quotes scan that blocks multi-statement smuggling.
    (fd2d682)
  - Metadata comments: `COMMENT ON ...` is allowed; all `CREATE ...` forms are
    blocked by policy.
  - `SqlIdentifiers`: validate-and-double-quote helper for schema/table
    identifiers and trusted string-literal assembly. (c641d61)
  - `db/setup_least_privilege_user.sql`: dedicated Oracle user with
    `CREATE SESSION` + `SELECT_CATALOG_ROLE` + per-object grants and **no**
    DDL/DCL privileges or tablespace quota by default.
- **61 MCP tools** across nine categories:
  - Schema introspection (12): schemas, tables, views, columns, indexes,
    constraints, sequences, triggers, objects, search, DDL source, PL/SQL.
  - Extended introspection (16): materialized views & logs, synonyms,
    partitions, subpartitions, index partitions, DB links, procedures,
    types, dependencies, invalid objects, external tables, directories,
    LOB columns, scheduler jobs & runs. (4849a49)
  - PL/SQL & optimizer stats (6): compile errors, table/column stats,
    histograms, session privs & roles. (b756507)
  - Performance diagnostics via `V$` views (13): active/blocked sessions,
    locks, session SQL text, top SQL, wait events, longops, instance &
    database info, system stats, time model, parameters, datafile IO.
    (6fd4250)
  - Index & query advisory (3): unused indexes, `suggest_index` via
    `EXPLAIN PLAN`, SQL Tuning Advisor report. (c4d834c)
  - Data helpers (4): `count_rows`, sequence `NEXTVAL`, FK integrity check,
    duplicate finder. (7cbee0d)
  - SQL & data (4): `run_query`, `execute_dml`, `get_sample_data`,
    `explain_plan`.
  - Metadata comments (2): comments on table/column.
  - System (1): `test_connection`.
- **Observability — Sentry.** Core SDK (`io.sentry:sentry` 8.16.0) with a
  manual `Sentry.init` and an `@AfterThrowing` AOP aspect
  (`SentryCaptureAspect`) that captures every uncaught exception thrown from
  the `service` package, filtering out the expected
  `OperationNotAllowedException` (SqlGuard denial). No Spring Boot starter,
  no logback appender. (3feca44)
- **Comprehensive Javadoc** on every public method (and meaningful private
  helpers) across all 35 main Java files — `@param`/`@return`/`@throws`.
- **CI** — GitHub Actions workflow (`.github/workflows/ci.yml`) running the
  Maven test suite. (8354023)
- **MIT License** (Copyright © 2026 Sadot Hdz). (8354023)
- **Agent workflow** — three specialised opencode agents (`.opencode/agents/`)
  forming a finalize-on-commit pipeline: `@code-review` (ocr CLI),
  `@docs-keeper` (doc/code sync), `@committer` (orchestrator + Conventional
  Commit). (b1539e8, 835a020, aa00de5)
- **Project skills** — `add-mcp-tool`, `oracle-dictionary-query`,
  `sql-guard-safety`, `open-code-review`, `commit-conventions`, `docs-sync`.

### Changed

- Refined opencode agent permissions and updated the documented tool count.
  (80c509f)
- Hardened `IndexAdvisorService` after `ocr` code review. (d86ad3d)
- Applied `ocr` review findings across security & docs; synced `AGENTS.md`.
  (01fb838)
- Migrated services to the `SqlIdentifiers` helper. (c641d61)
- Overhauled `README.md` with full tool reference, security model diagram,
  and client configuration examples. (8354023)

### Fixed

- `IndexAdvisorService`: addressed `ocr` findings. (d86ad3d)
- Security & docs: applied `ocr` review findings. (01fb838)

### Security

- Two-layer defence documented and enforced: application-layer `SqlGuard`
  **and** least-privilege database user. See `SECURITY.md` and `README.md`
  §Security model.
- One statement per tool call — batches are rejected by `SqlGuard`.
- No credentials in the repository; connection details come from
  environment variables (`ORACLE_DB_URL`, `ORACLE_DB_USERNAME`,
  `ORACLE_DB_PASSWORD`).
- STDIO transport only — no HTTP/SSE server is exposed; logs never go to
  stdout (it is the JSON-RPC channel).

[Unreleased]: https://github.com/zademy/mcp-oracle-db/commits/main
