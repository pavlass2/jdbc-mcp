# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Model Context Protocol (MCP) server that gives LLMs JDBC access to relational databases (PostgreSQL, MySQL, MariaDB, Oracle, SQL Server, SAP HANA, Informix, Firebird, HSQLDB, H2, Derby, SQLite). It is a fork of the Quarkiverse `quarkus-mcp-servers` JDBC server, built on Quarkus, packaged as a Docker image (`guang1/jdbc-mcp`) that exposes both MCP-SSE (HTTP) and MCP-STDIO transports.

The fork's value-add over upstream (see README.md vs README-org.md):
- Docker-first deployment (upstream is jbang-first)
- Write queries (INSERT/UPDATE/DELETE/CREATE TABLE) disabled by default; must be explicitly enabled
- Per-request JDBC credentials via HTTP headers instead of a single server-wide connection

## Build, test, run

```bash
./mvnw compile           # compile
./mvnw test               # unit tests
./mvnw package             # build runnable jar (target/quarkus-app/)
./mvnw quarkus:dev         # dev mode with live reload
```

Requires **JDK 17** (`maven.compiler.release=17`). CI (`.github/workflows/build-test.yml`) runs `mvnw compile` then `mvnw test` on every push/PR to `main`.

### Dependency resolution is pinned to Maven Central

`.mvn/maven.config` applies `-s .mvn/settings-central.xml` and `-Dmaven.repo.local=.mvn/repository` to every Maven invocation in this project. This deliberately bypasses `~/.m2/settings.xml`, which on the maintainer's machine installs a `<mirrorOf>*</mirrorOf>` mirror pointing at an internal Nexus over plain HTTP. Don't remove these or add a mirror to `settings-central.xml`. The project-local repository is gitignored, so a first build re-downloads (~150 MB).

### Tests

`src/test` covers the fork's own additions — the parts upstream doesn't have:

- `HttpHeaderParameterHelperTest` — plain JUnit + Mockito, no Quarkus boot. Covers individual headers, the `x-config` positional Base64 array, and its edge cases.
- `McpTestClient` — test-only MCP JSON-RPC client over the streamable-HTTP `/mcp` endpoint. Responses may be **plain JSON or SSE**: tools taking an `McpLog` (e.g. `list_tables`) and the imperatively-registered write tools answer with `text/event-stream`, so the client parses both.
- `ReadToolsTest`, `WriteToolsEnabledTest` (`@TestProfile` setting `enable.write.sql=true`), `SessionAffinityTest`, `SessionAffinityDisabledTest` (`@TestProfile` setting `jdbc.session.affinity=false`).

`McpTestClient` performs a real `initialize` + `notifications/initialized` handshake and echoes the `Mcp-Session-Id` header, because JDBC session affinity is keyed on the MCP connection id. Call `McpTestClient.newSession()` from `@BeforeAll` in each `@QuarkusTest` class; the session is then established lazily on first use, since RestAssured is not yet pointed at the test port during a static `@BeforeAll`.

Test classes sharing a test profile also share one Quarkus instance *and* one `JdbcSessionManager`, so assertions about retained connections must be relative (a delta), not absolute.

Tests run against in-memory H2 (`DB_CLOSE_DELAY=-1`), seeded via `DriverManager`. The database is shared across test classes for the whole surefire JVM, so a test class must not assume a clean database.

Two things worth knowing before changing test setup:

- **`quarkus.test.flat-class-path=true` in `application.properties` is load-bearing.** Without it every `@QuarkusTest` fails at boot with `ClassNotFoundException: io.quarkiverse.mcp.server.runtime.McpMessageHandler`, because the default split test classloader hides the in-repo copy of that class from the extension's beans. It affects tests only.
- **`SessionAffinityTest` is a characterization test asserting the *unwanted* current behaviour** (consecutive tool calls get different DB sessions). Connection-affinity work should make it fail; invert the assertion then rather than deleting it.

Run locally in HTTP/SSE mode:
```bash
./mvnw quarkus:dev
# or after packaging:
java -jar target/quarkus-app/quarkus-run.jar
```

Run in STDIO mode, required env/system properties:
```
quarkus.mcp.server.stdio.enabled=true
quarkus.log.console.enable=false
quarkus.log.console.stderr=false
```
(STDIO mode requires console logging disabled since stdout/stderr carry the JSON-RPC protocol.)

Docker images are built from `src/main/docker/Dockerfile.jvm` (the one used by CI/publish), with `.native`, `.native-micro`, and `.legacy-jar` variants also present but not used by the publish workflow. Release publishing (`.github/workflows/docker-publish.yml`) triggers on `v*` tags: builds with `mvnw package`, then pushes `Dockerfile.jvm` image tagged with the version (and `latest` if the tag is a plain semver).

## Architecture

### Single-file tool implementation

Nearly all server behavior lives in `src/main/java/io/quarkiverse/mcp/servers/jdbc/MCPServerJDBC.java`. It's a CDI bean using the `quarkus-mcp-server` annotation model (`@Tool`, `@ToolArg`, `@Prompt`, `@PromptArg`).

Key points:
- `registerDriver()` (`@Startup`) force-loads every supported JDBC driver class so `DriverManager` can find them regardless of which one a given connection URL needs.
- **`read_query` is a static `@Tool`** — always registered.
- **`write_query` and `create_table` are registered conditionally** at startup (`addTools()`, guarded by `enable.write.sql`) using the imperative `ToolManager` API instead of `@Tool` annotations, because Quarkus MCP has no way to conditionally include an annotated tool. Their `@Tool`-annotated method signatures are commented out directly above the real methods — keep both in sync if you change one.
- `write_query` rejects statements starting with `SELECT`; `create_table` requires `CREATE TABLE`. This is a naive prefix check, not real SQL parsing — treat as a basic guardrail, not a security boundary (see `docs/context_issue.md` for a real-world case where `read_query` executed an `INSERT`).
- Every tool borrows a connection through `lease(McpConnection)`, which delegates to `JdbcSessionManager`. The lease **must** be closed (try-with-resources) because it holds the session lock; whether the underlying connection is also closed depends on session affinity — see below. Every tool method therefore takes an `McpConnection` parameter.

### Per-request credentials via HTTP headers

`getConnection()` does not use a fixed datasource; it reads JDBC URL/user/password per-request from `HttpServerRequest` via `HttpHeaderParameterHelper`:
- Individual headers: `x-jdbc-url`, `x-jdbc-user`, `x-jdbc-password`.
- Or a single `x-config` header: dot-separated, Base64-encoded values in that same order (`x-jdbc-url.x-jdbc-user.x-jdbc-password`), added so MCP clients that only support one custom header (e.g. Roo Code) can still pass full connection config.

When adding new per-request parameters, both the individual-header path and the `x-config` positional-array path need updating together.

### Vendored/patched MCP runtime class

`src/main/java/io/quarkiverse/mcp/server/runtime/McpMessageHandler.java` lives in the **`io.quarkiverse.mcp.server.runtime`** package (not `...servers.jdbc`) — it's an in-repo override/patch of a class from the `quarkus-mcp-server` extension itself, not application code. If upstream JSON-RPC/session-handling behavior needs changing, this is where it happens; check it against the extension's released source when upgrading the `io.quarkiverse.mcp` dependency versions in `pom.xml` to see if the patch is still needed or needs re-applying.

### Session affinity (VPD / session-scoped state)

`JdbcSessionManager` keeps **one JDBC connection per MCP connection**, so session-scoped state — Oracle VPD context set via `DBMS_SESSION.SET_CONTEXT`, `ALTER SESSION`, session temp tables — survives from one tool call to the next. This resolves the problem analysed in `docs/context_issue.md`; before it, every call got a fresh connection and the context was always gone.

Things to know before changing it:

- **Keying is by MCP connection id.** Stable for STDIO and for SSE (one id per `/mcp/sse` stream). For streamable HTTP it is stable *only while the client echoes back the `Mcp-Session-Id` header*. A client that ignores that header gets a brand-new MCP connection per request — and the extension never removes those from its own `ConnectionManager` — so retained connections are capped (`jdbc.session.max`, LRU) and idle-expired (`jdbc.session.idle-timeout`). Do not remove those bounds.
- **Connections are validated (`isValid`) before reuse** and reopened if dead, so a DB restart or a dropped network connection self-heals. Ordinary SQL errors deliberately do *not* discard the connection — doing so would throw away the very session state this exists to preserve.
- **A lease holds a per-session `ReentrantLock`** for the whole tool call, so concurrent calls on one MCP connection serialize instead of racing on one non-thread-safe `Connection`.
- **Switching credentials** (different `x-jdbc-url`/user/password on the same MCP connection) closes the old connection and opens a new one — a different identity must never be handed someone else's session.
- `jdbc.session.affinity=false` restores the old connection-per-call behaviour.
- `JdbcSessionManager` injects the extension-internal `io.quarkiverse.mcp.server.runtime.ConnectionManager`, purely to notice that a client disconnected and release its DB session early. Check it when upgrading `io.quarkiverse.mcp` — same caveat as the vendored `McpMessageHandler`.

### Tools must not emit MCP log notifications

`list_tables` used to call `log.debug`/`log.error` on every invocation (leftover debugging, at ERROR severity). Emitting a notification while handling a streamable-HTTP request can consume the HTTP response before the tool result is written, failing the call with `IllegalStateException: Response has already been written`. If you add `McpLog` logging to a tool, verify it over the streamable-HTTP transport, not just SSE.

### Legacy TLS/crypto support

`src/main/docker/java.security` and the corresponding block in `Dockerfile.jvm` re-enable legacy TLS versions and SHA-1 at both the OS (`update-crypto-policies --set LEGACY`) and JVM level, to support connecting to old SQL Server instances that require SHA-1/legacy TLS. This is a deliberate compatibility tradeoff, not an oversight — don't "fix" it by removing the weakened algorithms without confirming legacy DB compatibility is no longer needed.

## Configuration reference

| Property / env var | Purpose | Default |
|---|---|---|
| `jdbc.url`, `jdbc.user`, `jdbc.password` | Fallback JDBC connection info (server-wide). **Not actually read** — this fork removed the config fallback; connection info comes from headers only. | - |
| `enable.write.sql` | Enables `write_query`/`create_table` tools | `false` |
| `jdbc.session.affinity` | Reuse one JDBC connection per MCP connection so session state survives across tool calls | `true` |
| `jdbc.session.idle-timeout` | How long a retained connection may sit unused before it is closed (ISO-8601 duration) | `PT10M` |
| `jdbc.session.max` | Cap on retained connections; least-recently-used are closed past it | `16` |
| `x-jdbc-url` / `x-jdbc-user` / `x-jdbc-password` (HTTP headers) | Per-request JDBC connection info, overrides server config | - |
| `x-config` (HTTP header) | Dot-separated Base64 values encoding the three headers above, for clients limited to one custom header | - |
| `quarkus.mcp.server.stdio.enabled` | Switch transport to STDIO | `false` |
