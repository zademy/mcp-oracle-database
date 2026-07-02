# Security Policy

This server executes untrusted AI-generated SQL against an Oracle database.
Security is a first-class concern and is enforced by a **single layer**: a
least-privilege Oracle user. A summary follows — see [README.md](README.md)
§*Security model* for the full diagram and `AGENTS.md` §3 for the contract
every change must obey.

## Table of contents

- [Supported versions](#supported-versions)
- [The security model](#the-security-model)
- [Reporting a vulnerability](#reporting-a-vulnerability)
- [Response process](#response-process)
- [Scope](#scope)
- [Hardening checklist for operators](#hardening-checklist-for-operators)
- [What is explicitly NOT covered](#what-is-explicitly-not-covered)

## Supported versions

| Version | Supported          |
|---------|--------------------|
| `main`  | ✅ active development |
| tagged releases | ✅ latest only     |

The project is pre-`0.1.0` (no tagged releases yet). Security fixes are
delivered to `main`; consumers should track `main` until the first release.

## The security model

```
            ┌────────────────┐        ┌──────────────────────────┐
AI SQL  ──▶ │ OracleDataAcc. │ ─────▶ │ Oracle (least-priv user) │
            └────────────────┘        └─────────────┬────────────┘
                                                      │ CREATE / ALTER / DROP / GRANT / …
                                                      ▼
                                            ┌────────────────────────┐
                                            │ ORA-01031 insufficient │
                                            │ privileges → error str │
                                            └────────────────────────┘
```

**Single layer — least-privilege database user.** The server connects as a
dedicated Oracle user with `CREATE SESSION` + `SELECT_CATALOG_ROLE` +
per-object `SELECT`/`INSERT`/`UPDATE`/`DELETE` grants, `EXECUTE` on specific
procedures/packages (à la carte), and **no** DDL/DCL privileges or tablespace
quota. There is no application-layer SQL gate — every statement a tool sends
is executed verbatim against Oracle, and Oracle's privilege model decides
allow/deny. Any `CREATE`/`ALTER`/`DROP`/`GRANT`/`REVOKE`/`TRUNCATE`/… comes
back from Oracle as a `DataAccessException` (typically
`ORA-01031: insufficient privileges`), which the tool layer converts into an
explanatory string. See `db/setup_least_privilege_user.sql`.

Internal metadata queries use `ALL_*` views with **named parameters**
(`:param`) — never string concatenation of user input. Identifiers
(schema/table names) are validated and double-quoted via `SqlIdentifiers`.

## Reporting a vulnerability

**Please do not open a public GitHub issue for security vulnerabilities.**

Instead, report privately using **one** of these channels:

1. **GitHub Private Security Advisory** (preferred):
   <https://github.com/zademy/mcp-oracle-db/security/advisories/new>
2. **GitHub Security Advisory on a confidential issue**, or
3. Email / direct message the maintainer via their GitHub profile:
   <https://github.com/sadot>.

Please include:

- A clear description of the issue and its potential impact.
- The exact input (SQL string, tool name, parameters) that reproduces it.
- Whether the issue is in the **services / tools** (e.g. unsafe identifier
  handling), the **least-privilege grants** (over-broad privileges on the
  Oracle user), or somewhere else (transport, dependencies, …).
- Any mitigations you have already considered.

You will receive an acknowledgement within **72 hours**. If a fix is agreed, a
coordinated disclosure will be planned and credit offered (unless you prefer
to remain anonymous).

## Response process

1. Acknowledge receipt within 72 hours.
2. Confirm reproduction and assess severity (impact on confidentiality,
   integrity, availability of the Oracle database the server connects to).
3. Agree a fix and a disclosure timeline (default: fix first, then publish a
   GitHub Security Advisory + `CHANGELOG.md` entry under `### Security`).
4. Credit the reporter in the advisory unless they ask to remain anonymous.

## Scope

**In scope:**

- Over-broad privileges on the least-privilege Oracle user — tablespace quota
  or any system/DDL/DCL privilege (`CREATE TABLE`, `ALTER`, `DROP`, `GRANT`,
  …) that lets a statement the model intends to block succeed against Oracle.
- SQL injection through the internal metadata path (named-parameter misuse,
  identifier concatenation bypassing `SqlIdentifiers`).
- `SqlIdentifiers` producing unsafe quoted identifiers.
- Exposure of credentials, connection strings, or other secrets through logs,
  error messages, or the JSON-RPC channel (stdout).
- Denial of service via unbounded queries or resource exhaustion (the
  `max-rows` / `query-timeout-seconds` caps are meant to bound this).
- Vulnerabilities in pinned dependencies that affect the server's security
  posture.

**Also welcome** (lower severity but appreciated):

- Hardening suggestions for `db/setup_least_privilege_user.sql`.
- Improvements to identifier validation (`SqlIdentifiers`) edge cases.

## Hardening checklist for operators

Before exposing this server to an MCP client, verify:

- [ ] The Oracle user used by `ORACLE_DB_USERNAME` was created with
      `db/setup_least_privilege_user.sql` (or an equivalent that grants only
      `CREATE SESSION`, `SELECT_CATALOG_ROLE`, and per-object grants).
- [ ] That user has **no** DDL/DCL privileges and **no** tablespace quota
      (unless one of the optional `CREATE_*` sections is explicitly enabled).
- [ ] `ORACLE_DB_PASSWORD` is a strong, rotated secret.
- [ ] The credentials are provided via environment variables and are **not**
      checked into the repository or logged.
- [ ] `oracle.mcp.max-rows` and `oracle.mcp.query-timeout-seconds` are tuned
      to your environment (defaults: `1000` and `30`).
- [ ] The server is reachable only by trusted MCP clients (STDIO transport;
      there is no HTTP/SSE surface to harden).
- [ ] If you enable Sentry observability, `sentry.send-default-pii` is `false`
      and your DSN/auth-token are treated as secrets.

## What is explicitly NOT covered

- **The Oracle database itself.** Hardening Oracle, patching the DB, and
  configuring listener security are the operator's responsibility. This
  project only enforces a least-privilege connection to it.
- **The MCP client.** Claude Desktop / Cursor / etc. run as local processes
  with the invoking user's privileges; this server does not attempt to sandbox
  them.
- **STDIO transport trust model.** The server speaks STDIO only and assumes a
  trusted local process as its peer. It is not designed to be exposed over a
  network without an additional trusted transport layer.
- **Outdated or end-of-life Java / Oracle JDBC versions.** Use the versions
  documented in [README.md](README.md) (JDK 26, `ojdbc17` 23.x).
- **Social engineering, phishing, or physical attacks** against operators or
  maintainers.
