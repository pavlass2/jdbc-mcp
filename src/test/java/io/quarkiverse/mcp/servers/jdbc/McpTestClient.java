package io.quarkiverse.mcp.servers.jdbc;

import static io.restassured.RestAssured.given;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;

/**
 * Minimal MCP JSON-RPC client for tests, speaking the streamable-HTTP transport on {@code /mcp}.
 *
 * <p>
 * Deliberately talks to the server the way a real MCP client does - over HTTP with the JDBC
 * credentials in request headers - rather than calling {@link MCPServerJDBC} directly. The
 * header-to-connection path is the part of this fork that upstream does not have, so it is the
 * part worth covering.
 *
 * <p>
 * No {@code initialize} handshake is performed: this fork patches
 * {@code McpMessageHandler} to accept a first message of any type. Tests therefore also act as a
 * regression check that the patch still applies after an extension upgrade.
 */
final class McpTestClient {

    /** In-memory fixture database. {@code DB_CLOSE_DELAY=-1} keeps it alive between connections. */
    static final String H2_URL = "jdbc:h2:mem:jdbcmcptest;DB_CLOSE_DELAY=-1";
    static final String H2_USER = "sa";
    // Non-empty on purpose: encodeBase64Values joins with '.', and String.split discards trailing
    // empty segments, so an empty password would arrive as null through the x-config path.
    static final String H2_PASSWORD = "sa";

    private static final AtomicInteger REQUEST_ID = new AtomicInteger();

    private McpTestClient() {
    }

    /** Sends a JSON-RPC request with the default H2 credential headers. */
    static JsonPath rpc(String method, Map<String, Object> params) {
        return rpc(method, params, Map.of(
                "x-jdbc-url", H2_URL,
                "x-jdbc-user", H2_USER,
                "x-jdbc-password", H2_PASSWORD));
    }

    /** Sends a JSON-RPC request with an explicit header set. */
    static JsonPath rpc(String method, Map<String, Object> params, Map<String, String> headers) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", REQUEST_ID.incrementAndGet());
        body.put("method", method);
        if (params != null) {
            body.put("params", params);
        }

        RequestSpecification request = given()
                .contentType("application/json")
                .accept("application/json, text/event-stream");
        headers.forEach(request::header);

        return parse(request.body(body)
                .when()
                .post("/mcp")
                .then()
                .statusCode(200)
                .extract()
                .asString());
    }

    /**
     * Parses a response body that may be either plain JSON or an SSE stream.
     *
     * <p>
     * The server picks the transport per call: a tool that emits MCP log notifications (anything
     * taking an {@code McpLog}, e.g. {@code list_tables}) or that was registered imperatively via
     * {@code ToolManager} answers with {@code text/event-stream}, where the JSON-RPC response
     * arrives as a {@code data:} line - possibly preceded by notification events, which are
     * skipped here.
     */
    private static JsonPath parse(String body) {
        String trimmed = body.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return JsonPath.from(trimmed);
        }

        for (String line : trimmed.split("\\R")) {
            if (!line.startsWith("data:")) {
                continue;
            }
            JsonPath event = JsonPath.from(line.substring("data:".length()).trim());
            if (event.get("result") != null || event.get("error") != null) {
                return event;
            }
        }
        throw new AssertionError("No JSON-RPC result or error found in response body:\n" + body);
    }

    /** Names of every tool the server currently advertises. */
    static List<String> toolNames() {
        return rpc("tools/list", Map.of()).getList("result.tools.name", String.class);
    }

    /** Calls a tool with the default H2 credentials and returns its text content. */
    static String callTool(String name, Map<String, Object> arguments) {
        return textOf(callToolRaw(name, arguments));
    }

    /** Calls a tool with an explicit header set and returns its text content. */
    static String callTool(String name, Map<String, Object> arguments, Map<String, String> headers) {
        return textOf(callToolRaw(name, arguments, headers));
    }

    /** Calls a tool and returns the whole JSON-RPC response, for inspecting errors. */
    static JsonPath callToolRaw(String name, Map<String, Object> arguments) {
        return rpc("tools/call", Map.of("name", name, "arguments", arguments));
    }

    static JsonPath callToolRaw(String name, Map<String, Object> arguments, Map<String, String> headers) {
        return rpc("tools/call", Map.of("name", name, "arguments", arguments), headers);
    }

    /**
     * First text content block of a successful tool result.
     *
     * @throws AssertionError if the response is a JSON-RPC error or a tool-level error
     */
    static String textOf(JsonPath response) {
        if (response.get("error") != null) {
            throw new AssertionError("Expected a successful tool call but got JSON-RPC error: "
                    + response.getString("error.message"));
        }
        if (Boolean.TRUE.equals(response.getBoolean("result.isError"))) {
            throw new AssertionError("Tool reported an error: " + response.getString("result.content[0].text"));
        }
        return response.getString("result.content[0].text");
    }

    /** Error text of a failed tool call, whether it failed at the JSON-RPC or the tool level. */
    static String errorTextOf(JsonPath response) {
        if (response.get("error") != null) {
            return response.getString("error.message");
        }
        return response.getString("result.content[0].text");
    }

    /** True if the response represents a failure of any kind. */
    static boolean isFailure(JsonPath response) {
        return response.get("error") != null || Boolean.TRUE.equals(response.getBoolean("result.isError"));
    }

    /** Runs a single tool call as a read_query, returning the parsed JSON rows. */
    static List<Map<String, String>> readQuery(String sql) {
        return JsonPath.from(callTool("read_query", Map.of("query", sql))).getList("");
    }
}
