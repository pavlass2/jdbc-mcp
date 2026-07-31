# JDBC-MCP - Database Access for AI Assistants

[![Docker Hub](https://img.shields.io/docker/pulls/guang1/jdbc-mcp.svg)](https://hub.docker.com/r/guang1/jdbc-mcp)
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
  guang1/jdbc-mcp:latest
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
  guang1/jdbc-mcp:latest
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
    image: guang1/jdbc-mcp:latest
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
    image: guang1/jdbc-mcp:latest
```

#### MCP SSE URL
```http://localhost:8080/mcp/sse```

### MCP STDIO
**Must** pass `quarkus.mcp.server.stdio.enabled=true`, `quarkus.log.console.enable=false` and `quarkus.log.console.stderr=false` environment variables with `-i` interactive option.
#### Simple usage
```
docker run --rm \
  -e enable.write.sql=false \
  -e jdbc.user=db_user \
  -e jdbc.password=db_password \
  -e jdbc.url=jdbc:postgresql://host:port/database \
  -e quarkus.mcp.server.stdio.enabled=true \
  -e quarkus.log.console.enable=false \
  -e quarkus.log.console.stderr=false \  
  guang1/jdbc-mcp:latest
``` 

#### Using an Environment File
```
docker run --rm 
  --env-file .env \
   -e quarkus.mcp.server.stdio.enabled=true \
   -e quarkus.log.console.enable=false \
   -e quarkus.log.console.stderr=false \  
  guang1/jdbc-mcp:latest
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
        "-e",
        "enable.write.sql=false",
        "-e",
        "jdbc.user=db_user",
        "-e",
        "jdbc.password=db_password",
        "-e",
        "jdbc.url=jdbc:postgresql://host:port/database",
        "-e",
        "quarkus.mcp.server.stdio.enabled=true",
        "-e",
        "quarkus.log.console.enable=false",
        "-e",
        "quarkus.log.console.stderr=false",
        "-i",
        "guang1/jdbc-mcp:latest"
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
        "--env-file",
        ".env",
        "-e",
        "quarkus.mcp.server.stdio.enabled=true",
        "-e",
        "quarkus.log.console.enable=false",
        "-e",
        "quarkus.log.console.stderr=false",
        "-i",
        "guang1/jdbc-mcp:latest"
      ]
    }
  }
}
```

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `jdbc.url` | JDBC connection URL (required) | - |
| `jdbc.user` | Database username | - |
| `jdbc.password` | Database password | - |
| `enable.write.sql` | Enable SQL operations that modify data | `false` |
| `jdbc.session.affinity` | Reuse one database connection per MCP client connection, so session state survives across tool calls (see below) | `true` |
| `jdbc.session.idle-timeout` | How long an unused database connection is kept before closing (ISO-8601 duration) | `PT10M` |
| `jdbc.session.max` | Maximum number of database connections kept open at once | `16` |

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

### Supported Database Types

This MCP server supports multiple database types through JDBC. For detailed information on supported JDBC variants, please see the [quarkus-mcp-servers/jdbc documentation](https://github.com/quarkiverse/quarkus-mcp-servers/blob/main/jdbc/README.md#supported-jdbc-variants).

## Security Considerations

⚠️ **Warning**: When `enable.write.sql` is set to `true`, the MCP server can execute SQL statements that modify data. Use with caution in production environments.

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

By default, write operations (INSERT, UPDATE, DELETE) are disabled for security. To enable them:

- When using Docker: add `-e enable.write.sql=true` as shown above

## Original Documentation

For all other features, including supported databases, general usage, example databases, MCP components, and troubleshooting, please refer to the original repository documentation:

[Quarkus MCP JDBC Server Documentation](https://github.com/quarkiverse/quarkus-mcp-servers/tree/main/jdbc)

## License

This project is licensed under the Apache License 2.0.

## Acknowledgments

- Original project: [Quarkiverse MCP Servers](https://github.com/quarkiverse/quarkus-mcp-servers)
- Built with [Quarkus](https://quarkus.io/)
- Containerized with [Docker](https://www.docker.com/) 