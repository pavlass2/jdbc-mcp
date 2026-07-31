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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;

/**
 * Coverage of the two tools that are registered imperatively at startup when
 * {@code enable.write.sql=true}.
 *
 * <p>
 * Registration happens in {@code MCPServerJDBC.addTools()} rather than through {@code @Tool}
 * annotations, so it is only exercised when the config property is set - hence the dedicated
 * profile and a separate Quarkus boot.
 */
@QuarkusTest
@TestProfile(WriteToolsEnabledTest.WriteEnabledProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WriteToolsEnabledTest {

    public static class WriteEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("enable.write.sql", "true");
        }
    }

    /**
     * The in-memory database is kept alive for the whole surefire JVM
     * ({@code DB_CLOSE_DELAY=-1}) and shared with the other test classes, so clear this class's
     * table up front rather than assuming a clean database.
     */
    @BeforeAll
    static void dropFixtureTable() throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                McpTestClient.H2_URL, McpTestClient.H2_USER, McpTestClient.H2_PASSWORD);
                Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS ORDERS");
        }
    }

    @Test
    @Order(1)
    @DisplayName("write tools are advertised once enable.write.sql is set")
    void writeToolsAdvertised() {
        List<String> tools = McpTestClient.toolNames();

        assertTrue(tools.contains("write_query"), "expected write_query, got: " + tools);
        assertTrue(tools.contains("create_table"), "expected create_table, got: " + tools);
    }

    @Test
    @Order(2)
    @DisplayName("create_table then write_query then read_query round-trips")
    void writeRoundTrip() {
        assertEquals("Query executed successfully", McpTestClient.callTool("create_table", Map.of(
                "query", "CREATE TABLE ORDERS (ID INT PRIMARY KEY, ITEM VARCHAR(50))")));

        assertEquals("Query executed successfully", McpTestClient.callTool("write_query", Map.of(
                "query", "INSERT INTO ORDERS VALUES (1, 'widget')")));

        List<Map<String, String>> rows = McpTestClient.readQuery("SELECT ITEM FROM ORDERS WHERE ID = 1");
        assertEquals(1, rows.size());
        assertEquals("widget", rows.get(0).get("ITEM"));

        assertEquals("Query executed successfully", McpTestClient.callTool("write_query", Map.of(
                "query", "UPDATE ORDERS SET ITEM = 'gadget' WHERE ID = 1")));
        assertEquals("gadget", McpTestClient.readQuery("SELECT ITEM FROM ORDERS WHERE ID = 1").get(0).get("ITEM"));

        assertEquals("Query executed successfully", McpTestClient.callTool("write_query", Map.of(
                "query", "DELETE FROM ORDERS WHERE ID = 1")));
        assertEquals(List.of(), McpTestClient.readQuery("SELECT ITEM FROM ORDERS WHERE ID = 1"));
    }

    @Test
    @Order(3)
    @DisplayName("write_query rejects SELECT regardless of case or leading whitespace")
    void writeQueryRejectsSelect() {
        for (String query : List.of("SELECT 1", "select 1", "  \n\t SeLeCt 1")) {
            JsonPath response = McpTestClient.callToolRaw("write_query", Map.of("query", query));

            assertTrue(McpTestClient.isFailure(response), "should have rejected: " + query);
            assertTrue(McpTestClient.errorTextOf(response).contains("SELECT queries are not allowed"),
                    "unexpected error for '" + query + "': " + McpTestClient.errorTextOf(response));
        }
    }

    @Test
    @Order(4)
    @DisplayName("create_table rejects statements that are not CREATE TABLE")
    void createTableRejectsOtherStatements() {
        for (String query : List.of("DROP TABLE ORDERS", "CREATE INDEX IDX ON ORDERS (ID)", "INSERT INTO ORDERS VALUES (9,'x')")) {
            JsonPath response = McpTestClient.callToolRaw("create_table", Map.of("query", query));

            assertTrue(McpTestClient.isFailure(response), "should have rejected: " + query);
            assertTrue(McpTestClient.errorTextOf(response).contains("Only CREATE TABLE statements are allowed"),
                    "unexpected error for '" + query + "': " + McpTestClient.errorTextOf(response));
        }
    }

    @Test
    @Order(5)
    @DisplayName("the guardrail is a naive prefix match, not SQL parsing")
    void guardrailIsPrefixMatchOnly() {
        // Characterization test, not an endorsement: a leading comment is enough to walk a
        // SELECT past write_query's check. The statement still fails, but in the JDBC driver
        // rather than in the guardrail - so the guardrail must not be treated as a security
        // boundary. See docs/context_issue.md for a real case of a write slipping through
        // read_query in the other direction.
        JsonPath response = McpTestClient.callToolRaw("write_query", Map.of("query", "/* comment */ SELECT 1"));

        assertTrue(McpTestClient.isFailure(response));
        assertFalse(McpTestClient.errorTextOf(response).contains("SELECT queries are not allowed"),
                "the prefix check was expected to be bypassed by the leading comment");
    }
}
