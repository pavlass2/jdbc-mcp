# VPD context does not persist across tool calls

Date: 2026-07-07

## Summary

An MCP server exposes an Oracle 19c database through tools
(`database_info`, `list_tables`, `describe_table`, `read_query`, `write_query`).
Data in this database is partitioned by "company" context via Oracle VPD
(row-level security), managed through the `APP_CONTEXT_PKG` package:

* `call app_context_pkg.set_active_company(1)` — activate context 1 (default;
  8 is also valid)
* `call app_context_pkg.switch_vpd_off()` — disable the VPD filter

In practice, calling `set_active_company` and then querying a VPD-protected
table (e.g. `PROTECTED_TABLE`) in a follow-up call always returns **0 rows**, even
though the table is not empty (`ALL_TABLES.NUM_ROWS` reports hundreds of millions of rows for
`PROTECTED_TABLE`).

## Root cause

Each MCP tool call (`read_query` / `write_query`) appears to run on a
**different pooled Oracle connection/session**, not a session that persists
across calls within the same chat/tool session. This was confirmed by
querying `SYS_CONTEXT('USERENV','SID')` twice in separate `read_query` calls
and observing different session IDs each time (e.g. `2301548428` vs `586`).

`APP_CONTEXT_PKG.set_active_company` / `switch_vpd_off` set **session-scoped**
state (almost certainly via `DBMS_SESSION.SET_CONTEXT` under an application
context namespace used by the VPD policy on `TENANT_ID`-bearing tables).
Since the next tool call gets a different session, the previously set
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

Any workflow that requires `set_active_company(1|8)` (or `switch_vpd_off`)
followed by a query against VPD-protected tables (e.g. `PROTECTED_TABLE`, and any
other table with a `TENANT_ID` column) **cannot currently be completed**
through this MCP server in more than one tool call, because there is no way
to guarantee the SET and the SELECT share the same Oracle session.

Unaffected: tables/queries that don't depend on VPD context work fine via
`read_query` (e.g. `SELECT * FROM NON_VPD_TABLE`, dictionary views like
`USER_TABLES`, `ALL_TAB_COLUMNS`).

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
