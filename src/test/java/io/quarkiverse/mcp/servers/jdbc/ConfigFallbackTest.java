package io.quarkiverse.mcp.servers.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;

/**
 * Covers the server-wide {@code jdbc.url}/{@code jdbc.user}/{@code jdbc.password} configuration.
 *
 * <p>
 * This is the only way to supply connection info under the STDIO transport, which has no HTTP
 * request to carry the {@code x-jdbc-*} headers - so it is what makes the published Docker image
 * usable from an MCP client that speaks STDIO.
 */
@QuarkusTest
@TestProfile(ConfigFallbackTest.ConfiguredConnectionProfile.class)
class ConfigFallbackTest {

    /** A second database, reachable only by overriding the configured one with headers. */
    private static final String OTHER_URL = "jdbc:h2:mem:configfallbackother;DB_CLOSE_DELAY=-1";

    public static class ConfiguredConnectionProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "jdbc.url", McpTestClient.H2_URL,
                    "jdbc.user", McpTestClient.H2_USER,
                    "jdbc.password", McpTestClient.H2_PASSWORD);
        }
    }

    @BeforeAll
    static void seedBothDatabases() throws SQLException {
        McpTestClient.newSession();
        try (Connection conn = DriverManager.getConnection(
                OTHER_URL, McpTestClient.H2_USER, McpTestClient.H2_PASSWORD);
                Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS ONLY_IN_OTHER_DB");
            stmt.execute("CREATE TABLE ONLY_IN_OTHER_DB (ID INT)");
            stmt.execute("INSERT INTO ONLY_IN_OTHER_DB VALUES (7)");
        }
    }

    @Test
    @DisplayName("a call with no JDBC headers uses the configured connection")
    void headerlessCallUsesConfiguredConnection() {
        List<Map<String, String>> rows = JsonPath
                .from(McpTestClient.callTool("read_query", Map.of("query", "SELECT 1 AS ONE"), Map.of()))
                .getList("");

        assertEquals("1", rows.get(0).get("ONE"));
    }

    @Test
    @DisplayName("JDBC headers override the configured connection")
    void headersOverrideConfiguration() {
        List<Map<String, String>> rows = JsonPath
                .from(McpTestClient.callTool(
                        "read_query",
                        Map.of("query", "SELECT ID FROM ONLY_IN_OTHER_DB"),
                        Map.of(
                                "x-jdbc-url", OTHER_URL,
                                "x-jdbc-user", McpTestClient.H2_USER,
                                "x-jdbc-password", McpTestClient.H2_PASSWORD)))
                .getList("");

        assertEquals("7", rows.get(0).get("ID"));
    }

    @Test
    @DisplayName("the configured database is genuinely a different one, so the override is real")
    void configuredDatabaseDoesNotSeeTheOtherDatabase() {
        JsonPath response = McpTestClient.callToolRaw(
                "read_query", Map.of("query", "SELECT ID FROM ONLY_IN_OTHER_DB"), Map.of());

        assertTrue(McpTestClient.isFailure(response),
                "the configured database must not contain the other database's table");
    }
}
