# VPD context does not persist across tool calls

Date: 2026-07-07

> **Status: fixed (2026-07-31).** See "Resolution" at the end of this document.
> The analysis below is kept as the record of the original diagnosis.
>
> Schema and object names below are placeholders — the case came from a private
> database. `APP_CONTEXT_PKG`, `PROTECTED_TABLE` and the column names stand in for
> the real ones; nothing about the diagnosis depends on what they were called.

## Summary

An MCP server exposed an Oracle 19c database through tools
(`database_info`, `list_tables`, `describe_table`, `read_query`, `write_query`).
Data in this database is partitioned by "company" context via Oracle VPD
(row-level security), managed through a context package:

* `call app_context_pkg.set_active_company(1)` — activate context 1 (one of
  several valid values)
* `call app_context_pkg.switch_vpd_off()` — disable the VPD filter

In practice, calling `set_active_company` and then querying a VPD-protected
table in a follow-up call always returns **0 rows**, even though the table is
not empty (`ALL_TABLES.NUM_ROWS` reports hundreds of millions of rows for it).

## Root cause

Each MCP tool call (`read_query` / `write_query`) appears to run on a
**different pooled Oracle connection/session**, not a session that persists
across calls within the same chat/tool session. This was confirmed by
querying `SYS_CONTEXT('USERENV','SID')` twice in separate `read_query` calls
and observing different session IDs each time (e.g. `2301548428` vs `586`).

`APP_CONTEXT_PKG.set_active_company` / `switch_vpd_off` set **session-scoped**
state (almost certainly via `DBMS_SESSION.SET_CONTEXT` under an application
context namespace used by the VPD policy on tables carrying a tenant-id
column). Since the next tool call gets a different session, the previously set
context is gone, so the VPD policy filters out all rows.

### Things tried that do NOT work around it

* Running `set_active_company(1)` via `write_query`, then `read_query` in a
  separate call — context lost, `get_active_company()` returns `NULL` again.
* Combining context-set and the protected query in a **single** SQL
  statement using an inline `WITH FUNCTION` (Oracle 12c+) that calls
  `set_active_company` before the table scan:

  ```sql
  WITH FUNCTION set_ctx(p NUMBER) RETURN NUMBER IS
  BEGIN
    APP_CONTEXT_PKG.set_active_company(p);
    RETURN p;
  END;
  SELECT * FROM PROTECTED_TABLE WHERE set_ctx(1) = 1 AND ROWNUM = 1;
  ```

  Still returns 0 rows — Oracle's VPD evaluates the policy predicate
  independently of user-supplied predicate ordering, so it cannot be
  bypassed this way (this is expected/correct Oracle security behavior,
  not a bug in Oracle).
* Forcing execution order with `/*+ MATERIALIZE */` on a CTE that calls the
  context-setting function before joining to the protected table — same
  result, 0 rows.
* `switch_vpd_off()` has the same problem — it also only affects the session
  it runs in.

## Impact

Any workflow that requires `set_active_company(...)` (or `switch_vpd_off`)
followed by a query against VPD-protected tables **cannot currently be
completed** through this MCP server in more than one tool call, because there
is no way to guarantee the SET and the SELECT share the same Oracle session.

Unaffected: tables/queries that don't depend on VPD context work fine via
`read_query`, as do dictionary views like `USER_TABLES` and `ALL_TAB_COLUMNS`.

## Other issues noticed during testing (same server)

* `list_tables` and `describe_table` fail with:
  `Non supported character set (add orai18n.jar in your classpath): EE8ISO8859P2`
  — likely triggered by LONG/CLOB-bearing dictionary views under this DB
  character set; `orai18n.jar` is missing from the driver's classpath.
* `read_query` does not strictly enforce read-only/SELECT-only statements —
  an `INSERT ... WHERE ROWNUM < 1` executed without error via `read_query`
  (it inserted 0 rows in this instance, so no data was affected, but the
  tool did not reject the statement based on type).

## Suggested fix direction

Give the underlying JDBC connection pool **session affinity per logical
MCP "session"** (or expose an explicit "acquire/release a sticky
connection" mechanism), so that a sequence of tool calls within one
conversation can reuse the same Oracle session. Alternatively, expose a
single tool that accepts a list of statements to run sequentially on one
connection (e.g. `set context` + `query`), or add first-class support for
setting a session context (company id) as a documented server parameter
applied once per underlying connection when it's created/reused.

Separately (lower priority): fix the `orai18n.jar` classpath issue for
`list_tables`/`describe_table`, and consider making `read_query` reject
non-SELECT statements (or `write_query` reject SELECT) to match documented
tool semantics.

## Resolution (2026-07-31)

### Session affinity — fixed

`JdbcSessionManager` now keeps one JDBC connection per MCP client connection
instead of opening and closing one per tool call. A sequence of tool calls in
one conversation therefore runs on a single Oracle session, so
`app_context_pkg.set_active_company(1)` in one call is still in effect for a
`SELECT` against a protected table in the next.

The intended workflow now works in two calls:

1. `call app_context_pkg.set_active_company(1)` (via `write_query`, which needs
   `enable.write.sql=true`)
2. `SELECT ... FROM PROTECTED_TABLE ...` (via `read_query`)

`switch_vpd_off()` works the same way. `database_info` reports
`session_state_persists_across_tool_calls`, so the model can tell whether it
may rely on this.

**Caveat that decides whether this works for a given client:** affinity is keyed
on the MCP connection id. That is stable for STDIO and for SSE. For the
streamable-HTTP transport the client must echo back the `Mcp-Session-Id` header
the server returns; a client that does not gets a fresh MCP connection - and so
a fresh Oracle session - on every call, exactly as before. If context still does
not persist, check that header first.

Retained connections are real Oracle sessions, so they are bounded: closed after
`jdbc.session.idle-timeout` (default 10 minutes) of inactivity, when the client
disconnects, or when `jdbc.session.max` (default 16) is exceeded. Set
`jdbc.session.affinity=false` to return to the old behaviour.

Covered by `SessionAffinityTest`, `SessionAffinityDisabledTest`, and a
temporary-table test in `WriteToolsEnabledTest` that fails without the fix.

### orai18n — fixed

`com.oracle.database.nls:orai18n` (version-matched to `ojdbc10`) is now a
runtime dependency, which is what the
`Non supported character set ... EE8ISO8859P2` failure in
`list_tables`/`describe_table` was asking for. **Not verified against the
original database** - it needs a run against an `EE8ISO8859P2` instance to
confirm.

### Also fixed along the way

`list_tables` sent a "Listing tables" MCP log notification on every call, at
both debug and *error* severity (leftover debugging). Beyond the noise, emitting
a notification mid-request could consume the HTTP response before the tool
result was written, failing the call with `Response has already been written`.
Both calls were removed.

### Not addressed

`read_query` still does not enforce SELECT-only, so the observation above about
an `INSERT` executing through it still stands. This was left alone deliberately:
with session affinity the natural way to run `call app_context_pkg.*` is through
a tool call, and tightening `read_query` now would break that before there is a
dedicated mechanism for session setup. The prefix checks on `write_query` /
`create_table` remain naive prefix matches, not a security boundary.
