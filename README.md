# JDBC-MCP - Database Access for AI Assistants

[![Docker Hub](https://img.shields.io/docker/pulls/pavelmichalec/ora-jdbc-mcp.svg)](https://hub.docker.com/r/pavelmichalec/ora-jdbc-mcp)
[![GitHub License](https://img.shields.io/github/license/quarkiverse/quarkus-mcp-servers)](https://github.com/quarkiverse/quarkus-mcp-servers/blob/main/LICENSE)

This is a fork of the [Quarkus MCP JDBC Server](https://github.com/quarkiverse/quarkus-mcp-servers) with added features:

- **Docker deployment** - Easily run the MCP SSE or MCP STDIO as a containerized application
- **Enhanced security** - Write queries (INSERT, UPDATE, DELETE) are disabled by default and need to be explicitly enabled

The Model Context Protocol (MCP) server enables AI assistants to interact with databases through JDBC connections, making it possible for LLMs to inspect, query, create, and modify database content.

## New Features

### Docker Deployment

You can now run the JDBC-MCP server in a Docker container.

### MCP SSE
#### Simple usage

```bash
docker run -d --name jdbc-mcp \
  -p 8080:8080 \
  -e enable.write.sql=false \
  -e jdbc.user=db_user \
  -e jdbc.password=db_password \
  -e jdbc.url=jdbc:postgresql://host:port/database \
  pavelmichalec/ora-jdbc-mcp:latest
```

#### Using an Environment File

Create a `.env` file:
```
enable.write.sql=false
jdbc.user=db_user
jdbc.password=db_password
jdbc.url=jdbc:postgresql://host:port/database
```

Then run:
```bash
docker run -d --name jdbc-mcp \
  -p 8080:8080 \
  --env-file .env \
  pavelmichalec/ora-jdbc-mcp:latest
```

### Docker Compose for SSE

#### Basic Example

```yaml
services:
  jdbc-mcp:
    container_name: jdbc-mcp
    ports:
      - 8080:8080
    environment:
      - enable.write.sql=false
      - jdbc.user=db_user
      - jdbc.password=db_password
      - jdbc.url=jdbc:postgresql://host:port/database
    restart: always
    image: pavelmichalec/ora-jdbc-mcp:latest
```

#### With Environment File

```yaml
services:
  jdbc-mcp:
    container_name: jdbc-mcp
    ports:
      - 8080:8080
    env_file:
      - .env
    restart: always
    image: pavelmichalec/ora-jdbc-mcp:latest
```

#### MCP SSE URL
```http://localhost:8080/mcp/sse```

### MCP STDIO

Pass `MCP_STDIO=true` along with the `-i` interactive option:

```
docker run --rm -i \
  -e MCP_STDIO=true \
  -e jdbc.url=jdbc:postgresql://host:port/database \
  -e jdbc.user=db_user \
  -e jdbc.password=db_password \
  pavelmichalec/ora-jdbc-mcp:latest
```

`-i` is required — without it the container gets no stdin and the MCP handshake never
completes.

Under STDIO the protocol travels over stdout and stderr, so console logging must be off or
the client sees log lines where it expects JSON. `MCP_STDIO=true` turns it off for you.
The longer form is still accepted, and setting any of these explicitly overrides the
defaults (useful when debugging startup):

```
-e quarkus.mcp.server.stdio.enabled=true \
-e quarkus.log.console.enable=false \
-e quarkus.log.console.stderr=false
```

#### Using an Environment File
```
docker run --rm -i \
  --env-file .env \
  -e MCP_STDIO=true \
  pavelmichalec/ora-jdbc-mcp:latest
```

### Claude Desktop Configuration (STDIO)
To use the Docker version with Claude Desktop, add this to your `claude_desktop_config.json` or `server_config.json` file:
#### Simple usage
```
{
  "mcpServers": {
    "jdbc": {
      "command": "docker",
      "args": [
        "run",
        "--rm",
        "-i",
        "-e",
        "MCP_STDIO=true",
        "-e",
        "jdbc.url=jdbc:postgresql://host:port/database",
        "-e",
        "jdbc.user=db_user",
        "-e",
        "jdbc.password=db_password",
        "pavelmichalec/ora-jdbc-mcp:latest"
      ]
    }
  }
}
```

#### Using an Environment File
```
{
  "mcpServers": {
    "jdbc": {
      "command": "docker",
      "args": [
        "run",
        "--rm",
        "-i",
        "--env-file",
        ".env",
        "-e",
        "MCP_STDIO=true",
        "pavelmichalec/ora-jdbc-mcp:latest"
      ]
    }
  }
}
```

## Using it on another machine (no clone, no build)

Any machine with Docker can run the server without checking out this repository:

```bash
docker pull pavelmichalec/ora-jdbc-mcp:latest
```

Tagged releases go to two registries, from two independent workflows, so a missing
Docker Hub secret cannot stop the GHCR publish:

| Registry | Image | Workflow | Auth to pull |
|---|---|---|---|
| Docker Hub | `pavelmichalec/ora-jdbc-mcp` | `.github/workflows/docker-publish.yml` | none, if the repository is public |
| GHCR | `ghcr.io/<owner>/jdbc-mcp` | `.github/workflows/ghcr-publish.yml` | `docker login ghcr.io`, unless the package is made public |

Pushing a `v*` git tag triggers both. Only a plain version number (`v1.0`, `v1.2.3`) also
moves `:latest`, so a release candidate cannot become what everyone pulls by default.

```bash
git tag v1.0 && git push origin v1.0
```

### Publishing by hand

To get a build out without going through CI — `docker login` first.

Linux, macOS, Git Bash / WSL:

```bash
./scripts/publish.sh 1.0                       # pavelmichalec/ora-jdbc-mcp:1.0 and :latest
IMAGE=ghcr.io/me/thing ./scripts/publish.sh 1.0
PUSH=false ./scripts/publish.sh 1.0            # build and tag locally, push nothing
```

PowerShell (note that `./scripts/publish.sh` from PowerShell hands the file to Git Bash,
which usually has a different `JAVA_HOME` — use this instead):

```powershell
.\scripts\publish.ps1 1.0
.\scripts\publish.ps1 1.0 -Image ghcr.io/me/thing
.\scripts\publish.ps1 1.0 -NoPush
.\scripts\publish.ps1 1.0 -JavaHome C:\path\to\jdk-17    # if the shell default is not 17
```

Both run the test suite as part of the build (the Oracle container tests stay opt-in),
check for JDK 17 up front, and apply the same `:latest` rule as CI. The PowerShell version
restores your ambient `JAVA_HOME` on exit.

### GitHub Copilot CLI (`~/.copilot/mcp-config.json`)

STDIO transport, so there is no port to manage and no server to keep running - Copilot
starts the container on demand:

```json
{
  "mcpServers": {
    "my-database": {
      "tools": ["*"],
      "type": "stdio",
      "command": "docker",
      "args": [
        "run", "--rm", "-i",
        "-e", "MCP_STDIO=true",
        "-e", "jdbc.url=jdbc:oracle:thin:@//dbhost:1521/SERVICE",
        "-e", "jdbc.user=db_user",
        "-e", "jdbc.password=db_password",
        "pavelmichalec/ora-jdbc-mcp:latest"
      ]
    }
  }
}
```

Add `-e enable.write.sql=true` only if the assistant needs to modify data — or to run a
PL/SQL block, such as one that sets up an Oracle VPD context.

`--rm` keeps a container from being left behind per invocation.

Note that this file stores database credentials in plain text. Restrict its permissions,
and prefer an account with only the rights the assistant actually needs.

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `jdbc.url` | JDBC connection URL (required) | - |
| `jdbc.user` | Database username | - |
| `jdbc.password` | Database password | - |
| `enable.write.sql` | Enable SQL operations that modify data, and the `run_script` tool (see below) | `false` |
| `jdbc.session.affinity` | Reuse one database connection per MCP client connection, so session state survives across tool calls (see below) | `true` |
| `jdbc.session.idle-timeout` | How long an unused database connection is kept before closing (ISO-8601 duration) | `PT10M` |
| `jdbc.session.max` | Maximum number of database connections kept open at once | `16` |
| `jdbc.session.lock-timeout` | How long a tool call waits for a session already in use before it cancels the statement holding it (ISO-8601 duration) | `PT30S` |
| `jdbc.session.cancel-on-contention` | Allow a waiting tool call to cancel the statement blocking its session (see below) | `true` |
| `jdbc.query.timeout` | Seconds a single statement may run before the driver aborts it; `0` disables the limit | `120` |
| `jdbc.query.max-rows` | Rows `read_query` returns at most; `0` disables the limit | `1000` |
| `MCP_STDIO` | Switch to the STDIO transport, turning off console logging with it | `false` |

### Session state across tool calls

By default the server keeps one database connection per MCP client connection, so
session-scoped state set in one tool call is still in effect for the next one — for
example an Oracle VPD context set through `DBMS_SESSION.SET_CONTEXT`, an
`ALTER SESSION`, or a session-scoped temporary table. Without this, each call ran on
its own connection and such state was always lost.

This is keyed on the MCP connection, which is stable for the STDIO and SSE transports.
Clients using the streamable-HTTP transport must echo back the `Mcp-Session-Id` header
the server returns, otherwise every call is treated as a new client and gets a new
database session. Set `jdbc.session.affinity=false` to disable the behaviour entirely.

### Runaway queries

Because a connection is reused, it is also locked for the duration of each tool call —
two calls on one MCP connection cannot run at the same time. That makes a slow query
everybody's problem: while it runs, every later call on the same connection waits, and
the client that asked for it has usually given up long before it finishes.

Three bounds keep that from wedging the server:

- **`jdbc.query.timeout`** (default 120 s) is applied to every statement, so the driver
  aborts a runaway query instead of letting it hold the session indefinitely.
- **`jdbc.query.max-rows`** (default 1000) caps what `read_query` returns. When a result
  is cut, the response is an object — `{"rows_truncated": true, "row_limit": N, "rows": [...]}` —
  instead of the usual bare array, so the caller can tell the difference between "that was
  everything" and "there was more". Narrow the query or add your own `LIMIT`/`FETCH FIRST`.
- **`jdbc.session.lock-timeout`** (default 30 s) bounds how long a call waits for a busy
  session. Past it, the statement holding the session is cancelled and the lock taken over,
  which is what lets the server recover on its own from an abandoned call. Set
  `jdbc.session.cancel-on-contention=false` to fail the waiting call instead — but note that
  a genuinely stuck query will then keep blocking the connection until it ends by itself.

### Multi-statement scripts and PL/SQL

With `enable.write.sql=true` the server also offers a `run_script` tool, which takes a whole
script the way you would paste it into SQL\*Plus or SQL Developer, instead of a single
statement. This is how an assistant runs a PL/SQL block, creates a stored procedure or
trigger, or runs several statements that have to happen in order on one database session.

Statement terminators follow SQL\*Plus, because that is the convention the script was
almost certainly written for:

- plain SQL ends at a semicolon;
- a PL/SQL block is full of its own semicolons, so it ends at a line containing nothing but
  a forward slash.

```sql
ALTER SESSION SET NLS_DATE_FORMAT = 'YYYY-MM-DD';

BEGIN
  DBMS_SESSION.SET_CONTEXT('my_ctx', 'org_id', '42');
  DBMS_OUTPUT.PUT_LINE('context set');
END;
/

SELECT COUNT(*) AS n FROM orders;
```

Each statement is reported back separately as JSON — the line it started on, whether it
succeeded, its rows or update count, and the database's own error message if it failed.
Execution stops at the first error unless the assistant passes `continue_on_error`.

On Oracle, `DBMS_OUTPUT` is enabled and drained automatically and its lines are returned
with each statement, so `DBMS_OUTPUT.PUT_LINE` works as a way to get values out of a block.
`SET SERVEROUTPUT ON` is unnecessary.

Everything else SQL\*Plus does on the client side is not available, since none of it ever
reaches the database: substitution variables (`&name`, `DEFINE`), bind variables
(`VARIABLE`, `PRINT`), `EXEC`, and `SHOW ERRORS`. `SET`, `PROMPT`, `COLUMN`, `WHENEVER`,
`TTITLE` and `REM` lines are skipped and reported as such; `SPOOL` and `@file` are rejected,
the latter because reading files off the server's filesystem is not something this server
will do.

> ⚠️ A PL/SQL block can do anything the database user is allowed to do, so `run_script` is
> strictly more powerful than `write_query` and there is no meaningful read-only version of
> it. It is only registered when `enable.write.sql=true`.

### Supported Database Types

This MCP server supports multiple database types through JDBC. For detailed information on supported JDBC variants, please see the [quarkus-mcp-servers/jdbc documentation](https://github.com/quarkiverse/quarkus-mcp-servers/blob/main/jdbc/README.md#supported-jdbc-variants).

## Security Considerations

⚠️ **Warning**: When `enable.write.sql` is set to `true`, the MCP server can execute SQL statements that modify data — and, through `run_script`, arbitrary PL/SQL. Use with caution in production environments.

Consider these best practices:
- Use read-only database users when possible
- Implement network-level access controls
- Run in an isolated network environment

## Using with LLMs

This MCP server provides a standardized API that Large Language Models can use to interact with your database. To connect your LLM:

1. Configure the LLM to use the MCP server endpoint
2. Define the capabilities you want to allow (read-only vs. read-write)
3. Ensure your database schema is accessible to the MCP server
### Write Query Security

By default, write operations (INSERT, UPDATE, DELETE) and `run_script` are disabled for security. To enable them:

- When using Docker: add `-e enable.write.sql=true` as shown above

Because a PL/SQL block runs with all the rights of the connecting user, prefer a database
account restricted to what the assistant actually needs rather than relying on the tool
layer to limit it.

## Original Documentation

For all other features, including supported databases, general usage, example databases, MCP components, and troubleshooting, please refer to the original repository documentation:

[Quarkus MCP JDBC Server Documentation](https://github.com/quarkiverse/quarkus-mcp-servers/tree/main/jdbc)

## License

This project is licensed under the Apache License 2.0.

## Acknowledgments

- Original project: [Quarkiverse MCP Servers](https://github.com/quarkiverse/quarkus-mcp-servers)
- Built with [Quarkus](https://quarkus.io/)
- Containerized with [Docker](https://www.docker.com/) 