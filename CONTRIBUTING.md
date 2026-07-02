# Contributing to mcp-oracle-db

Thanks for your interest in contributing! This document covers the practical
basics. For architecture, conventions, and the security contract that every
change must respect, read **[AGENTS.md](AGENTS.md)** first — it is the source
of truth for this repository.

## Table of contents

- [Prerequisites](#prerequisites)
- [Getting the source](#getting-the-source)
- [Build](#build)
- [Tests](#tests)
- [Project layout](#project-layout)
- [Before you write code](#before-you-write-code)
- [Code style & conventions](#code-style--conventions)
- [Security-sensitive changes](#security-sensitive-changes)
- [Commit messages](#commit-messages)
- [Pull requests](#pull-requests)
- [Agent-assisted workflow](#agent-assisted-workflow)

## Prerequisites

| Tool            | Version        | Notes                                            |
|-----------------|----------------|--------------------------------------------------|
| JDK             | 26             | Required; the build targets Java 26.             |
| Apache Maven    | 3.9+           | The included `./mvnw` wrapper is enough.         |
| Oracle Database | 12c+           | Only needed for the env-gated integration test.  |
| An MCP client   | STDIO-capable  | Claude Desktop, Cursor, … for manual end-to-end. |

No database is required to build or run the unit tests.

## Getting the source

```bash
git clone https://github.com/zademy/mcp-oracle-db.git
cd mcp-oracle-db
```

If you plan to contribute back, fork first and add your fork as a remote.

## Build

```bash
./mvnw clean compile                  # compile only
./mvnw clean package -DskipTests      # produces target/mcp-oracle-db-0.0.1-SNAPSHOT.jar
```

## Tests

```bash
./mvnw test                           # unit tests (no DB needed)
```

The Spring `contextLoads` integration test is **environment-gated**: it only
runs when `ORACLE_DB_URL` is set, so `./mvnw test` is green without a database.

```bash
ORACLE_DB_URL=jdbc:oracle:thin:@//host:1521/SVC \
ORACLE_DB_USERNAME=mcp_user ORACLE_DB_PASSWORD=... \
./mvnw test
```

### Sentry smoke tests

The `SentrySmokeTest` sends real events to Sentry and is disabled by default.
Run it explicitly with:

```bash
./mvnw -Dtest=SentrySmokeTest -Dsentry.smoke=true test
```

## Project layout

```
src/main/java/com/zademy/mcp/oracle/db/
  McpOracleDbApplication.java            entry point
  config/                                properties + JDBC + Sentry config
  model/                                 records returned to clients
  security/                              SqlIdentifiers (identifier validation)
  persistence/                           OracleDataAccess (raw + named-param + callable)
  service/                               one service per domain
  tools/                                 @McpTool endpoints (11 files, 70 tools)
  observability/                         SentryCaptureAspect
src/test/java/.../security/             SqlIdentifiersTest
src/test/java/.../service/              IndexAdvisorServiceTest
src/test/java/.../observability/        Sentry unit + AOP wiring + smoke tests
db/setup_least_privilege_user.sql       least-privilege Oracle user
.github/workflows/ci.yml                CI
skills/                                  project-local recipes for agents
.opencode/agents/                        opencode agents (@code-review, @docs-keeper, @committer)
```

See `AGENTS.md` §2 (Architecture) and §4 (File map) for the full contract —
**dependencies point inward** (tools → services → security/persistence).

## Before you write code

1. **Read `AGENTS.md`** — especially §3 (Security model) and §6 (Conventions).
   Every change is expected to respect the layered architecture and the
   least-privilege security model (Oracle is the gate).
2. **Open an issue first** for anything beyond a typo or a small fix. This
   avoids wasted work when the change conflicts with the project direction.
3. **Keep changes small and focused.** One logical change per PR is strongly
   preferred (see `AGENTS.md` §6 and the `incremental-implementation` skill).

## Code style & conventions

- **Language:** all code, commit messages, docs, and review output are in
  **English**.
- **Indentation:** tabs; no trailing whitespace.
- **Models** are immutable **records**.
- **Services** use constructor injection (no `@Autowired`).
- **Internal metadata queries** use `ALL_*` views with named parameters
  (`:param`). Never concatenate user input into SQL.
- **Identifiers** (schema/table names) must go through `SqlIdentifiers`
  (validate + double-quote).
- **Tools** are thin adapters: validate input, call a service, return a result
  or a denial string. No SQL in the `tools` package.
- **Comments** — `AGENTS.md` §6 says *"no comments unless they explain
  non-obvious intent."* Javadoc on public methods is the exception and is
  expected (every public method has `@param`/`@return`/`@throws`).
- **STDIO transport:** do **not** log to stdout during operation — stdout is
  the JSON-RPC channel. Logs go to stderr/file.
- **No secrets** in the repository. Credentials come from environment
  variables.

## Security-sensitive changes

This server runs untrusted AI-generated SQL against an Oracle database.
Security is the first review axis, not an afterthought.

- **Security is single-layer: the least-privilege Oracle user is the only
  barrier.** There is no application-layer SQL gate. Never add tablespace
  quota or any system/DDL/DCL privilege (`CREATE TABLE`, `ALTER`, `DROP`,
  `GRANT`, …) to that user without explicit approval — see `AGENTS.md` §3/§8.
- **`EXECUTE` à la carte** on specific procedures/packages is the only widening
  permitted (needed by `call_procedure`).
- **Adding a new MCP tool** that runs user SQL → send it verbatim to Oracle
  via `OracleDataAccess`; catch `DataAccessException` (Oracle errors) and
  `IllegalArgumentException` (input errors), returning a string.
- **Parameterised queries everywhere.** Identifiers via `SqlIdentifiers`.
- **One statement per tool call.** Do not add batch/multi-statement support.

When in doubt, follow the `add-mcp-tool` skill under `skills/` and the
security contract in `AGENTS.md` §3.

## Commit messages

This project uses [**Conventional Commits**](https://www.conventionalcommits.org/):

```
<type>(<scope>): <subject>

<body — markdown, explain the *why*>

<footer — BREAKING CHANGE: … or Closes #123>
```

Common types: `feat`, `fix`, `docs`, `refactor`, `test`, `chore`, `ci`,
`perf`, `security`. Scope is optional but encouraged (e.g. `feat(tools):`,
`fix(security):`). Subject ≤ 72 chars, imperative mood.

Examples from this repo's history:

```
feat(tools): add index/performance advisory tools
fix(security,docs): apply ocr review findings + sync AGENTS.md
security(ddl): block CREATE tools while keeping row DELETE
```

## Pull requests

1. Branch from `main`. Name the branch `<type>/<short-slug>` (e.g.
   `feat/mview-refresh-tool`).
2. Keep the PR **small and focused** — one logical change. Large PRs are hard
   to review safely, especially when they touch `security/`.
3. Make sure the following pass locally before pushing:
   ```bash
   ./mvnw -q -DskipTests compile    # clean compile
   ./mvnw test                      # unit tests green
   ```
4. If your change affects SQL classification, identifiers, or anything in
   `security/`, **add or update tests** in `SqlIdentifiersTest`.
5. Update `README.md` and `AGENTS.md` if behaviour, setup, tool list, or the
   security model changes. (The `@docs-keeper` agent can do this for you.)
6. Reference the issue in the PR description (`Closes #123`).
7. Do **not** push directly to `main`. Do **not** commit secrets, build output
   (`target/`), or IDE files.

### CI

GitHub Actions (`.github/workflows/ci.yml`) runs `./mvnw test` on every push
and pull request. Your PR must be green before merge.

## Agent-assisted workflow

This repo is designed to be developed alongside AI coding agents. Three
opencode agents live under `.opencode/agents/` and form a finalize-on-commit
pipeline (full contract in `AGENTS.md` §12):

| Agent          | Role                                                                 |
|----------------|----------------------------------------------------------------------|
| `@code-review` | Runs the `ocr` CLI on current changes; applies safe high-confidence fixes. |
| `@docs-keeper` | Syncs `AGENTS.md` / `README.md` / agent files / skills index with code. |
| `@committer`   | **Finalize entry**: chains review → docs → verify → selective stage → Conventional Commit (no push). |

Typical finalize:

```
@committer  →  dispatches @code-review (gates on 🔴)  →  dispatches @docs-keeper
            →  ./mvnw compile && test  →  selective git add  →  commit
```

You are not required to use these agents to contribute — but if you do, they
will enforce the conventions above automatically. `@committer` never pushes;
the final `git push` is always a human action.

---

By contributing, you agree that your contributions will be licensed under the
[MIT License](LICENSE). This project follows the
[Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md); please be
respectful in all interactions.
