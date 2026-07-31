package io.quarkiverse.mcp.servers.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;

/**
 * End-to-end coverage of the always-registered read-only tools, driven over the MCP HTTP
 * transport against an in-memory H2 database.
 */
@QuarkusTest
class ReadToolsTest {

    @BeforeAll
    static void seedDatabase() throws SQLException {
        McpTestClient.newSession();
        try (Connection conn = DriverManager.getConnection(
                McpTestClient.H2_URL, McpTestClient.H2_USER, McpTestClient.H2_PASSWORD);
                Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS CUSTOMERS");
            stmt.execute("""
                    CREATE TABLE CUSTOMERS (
                        ID INT PRIMARY KEY,
                        NAME VARCHAR(100) NOT NULL,
                        COUNTRY VARCHAR(2),
                        NOTE VARCHAR(255)
                    )
                    """);
            stmt.execute("INSERT INTO CUSTOMERS VALUES (1, 'Alice', 'CZ', NULL)");
            stmt.execute("INSERT INTO CUSTOMERS VALUES (2, 'Bob', 'SK', 'vip')");
        }
    }

    @Test
    @DisplayName("only the read-only tools are advertised by default")
    void readOnlyToolsByDefault() {
        List<String> tools = McpTestClient.toolNames();

        assertTrue(tools.containsAll(List.of("read_query", "list_tables", "describe_table", "database_info")),
                "expected the read tools, got: " + tools);
        assertFalse(tools.contains("write_query"), "write_query must stay unregistered unless enable.write.sql=true");
        assertFalse(tools.contains("create_table"), "create_table must stay unregistered unless enable.write.sql=true");
    }

    @Test
    @DisplayName("read_query returns the selected rows as JSON")
    void readQueryReturnsRows() {
        List<Map<String, String>> rows = McpTestClient
                .readQuery("SELECT ID, NAME FROM CUSTOMERS ORDER BY ID");

        assertEquals(2, rows.size());
        assertEquals("1", rows.get(0).get("ID"));
        assertEquals("Alice", rows.get(0).get("NAME"));
        assertEquals("Bob", rows.get(1).get("NAME"));
    }

    @Test
    @DisplayName("read_query preserves SQL NULL as JSON null rather than the string \"null\"")
    void readQueryPreservesNulls() {
        List<Map<String, String>> rows = McpTestClient
                .readQuery("SELECT NOTE FROM CUSTOMERS WHERE ID = 1");

        assertEquals(1, rows.size());
        // quarkus.jackson.serialization-inclusion=non_null drops the key entirely.
        assertFalse(rows.get(0).containsValue("null"), "a SQL NULL must not surface as the string \"null\"");
    }

    @Test
    @DisplayName("read_query on an empty result set returns an empty array, not an error")
    void readQueryEmptyResult() {
        assertEquals(List.of(), McpTestClient.readQuery("SELECT * FROM CUSTOMERS WHERE 1 = 0"));
    }

    @Test
    @DisplayName("invalid SQL surfaces as a tool error, not a transport failure")
    void invalidSqlIsReportedAsToolError() {
        JsonPath response = McpTestClient.callToolRaw("read_query", Map.of("query", "SELECT * FROM NO_SUCH_TABLE"));

        assertTrue(McpTestClient.isFailure(response), "expected a failure for a query against a missing table");
        assertTrue(McpTestClient.errorTextOf(response).contains("Query execution failed"),
                "unexpected error text: " + McpTestClient.errorTextOf(response));
    }

    @Test
    @DisplayName("list_tables reports the seeded table")
    void listTablesReportsSeededTable() {
        List<Map<String, String>> tables = JsonPath
                .from(McpTestClient.callTool("list_tables", Map.of()))
                .getList("");

        assertTrue(tables.stream().anyMatch(t -> "CUSTOMERS".equals(t.get("TABLE_NAME"))),
                "CUSTOMERS missing from: " + tables);
    }

    @Test
    @DisplayName("describe_table reports column names, types and nullability")
    void describeTableReportsColumns() {
        List<Map<String, String>> columns = JsonPath
                .from(McpTestClient.callTool("describe_table", Map.of("table", "CUSTOMERS")))
                .getList("");

        assertEquals(List.of("ID", "NAME", "COUNTRY", "NOTE"),
                columns.stream().map(c -> c.get("COLUMN_NAME")).toList());

        Map<String, String> name = columns.get(1);
        assertEquals("CHARACTER VARYING", name.get("TYPE_NAME"));
        assertEquals("NO", name.get("NULLABLE"), "NAME is declared NOT NULL");
        assertEquals("YES", columns.get(2).get("NULLABLE"));
    }

    @Test
    @DisplayName("database_info identifies the connected database")
    void databaseInfoIdentifiesDatabase() {
        Map<String, String> info = JsonPath
                .from(McpTestClient.callTool("database_info", Map.of()))
                .getMap("");

        assertEquals("H2", info.get("database_product_name"));
        assertTrue(info.containsKey("sql_keywords"), "sql_keywords drives dialect selection by the LLM");
        // The URL and username are deliberately not exposed - they would leak the caller's
        // credentials back through the tool result.
        assertFalse(info.containsKey("url"));
        assertFalse(info.containsKey("username"));
    }

    @Test
    @DisplayName("credentials can be supplied through the single x-config header")
    void xConfigHeaderCarriesCredentials() {
        String config = HttpHeaderParameterHelper.encodeBase64Values(
                new String[] { McpTestClient.H2_URL, McpTestClient.H2_USER, McpTestClient.H2_PASSWORD });

        String result = McpTestClient.callTool(
                "read_query",
                Map.of("query", "SELECT NAME FROM CUSTOMERS WHERE ID = 2"),
                Map.of("x-config", config));

        assertTrue(result.contains("Bob"), "unexpected result: " + result);
    }

    @Test
    @DisplayName("a call with no credential headers fails when no connection is configured either")
    void missingCredentialsFail() {
        // This profile sets no jdbc.url, so there is nothing to fall back to. When one *is*
        // configured the call succeeds instead - see ConfigFallbackTest.
        JsonPath response = McpTestClient.callToolRaw(
                "read_query", Map.of("query", "SELECT 1"), Map.of());

        assertTrue(McpTestClient.isFailure(response),
                "with neither headers nor jdbc.url configured there is no connection to make");
    }
}
