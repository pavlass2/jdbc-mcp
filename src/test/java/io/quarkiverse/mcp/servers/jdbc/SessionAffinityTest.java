package io.quarkiverse.mcp.servers.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import jakarta.inject.Inject;

/**
 * Covers the fix for {@code docs/context_issue.md}: consecutive tool calls from one MCP client must
 * run on the same database session, so session-scoped state (an Oracle VPD context, an
 * {@code ALTER SESSION}, a temporary table) is still there for the next call.
 *
 * <p>
 * H2's {@code SESSION_ID()} stands in for the Oracle {@code SYS_CONTEXT('USERENV','SID')} used to
 * diagnose the original problem.
 */
@QuarkusTest
class SessionAffinityTest {

    /** A second identity on the same database, to exercise a credential switch. */
    private static final String OTHER_USER = "OTHER";
    private static final String OTHER_PASSWORD = "other";

    @Inject
    JdbcSessionManager sessions;

    @BeforeAll
    static void createSecondUser() throws SQLException {
        McpTestClient.newSession();
        try (Connection conn = DriverManager.getConnection(
                McpTestClient.H2_URL, McpTestClient.H2_USER, McpTestClient.H2_PASSWORD);
                Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE USER IF NOT EXISTS " + OTHER_USER + " PASSWORD '" + OTHER_PASSWORD + "' ADMIN");
        }
    }

    private static String sessionId() {
        return sessionId(McpTestClient.H2_USER, McpTestClient.H2_PASSWORD);
    }

    private static String sessionId(String user, String password) {
        List<Map<String, String>> rows = io.restassured.path.json.JsonPath
                .from(McpTestClient.callTool(
                        "read_query",
                        Map.of("query", "SELECT SESSION_ID() AS SID"),
                        Map.of(
                                "x-jdbc-url", McpTestClient.H2_URL,
                                "x-jdbc-user", user,
                                "x-jdbc-password", password)))
                .getList("");
        String sessionId = rows.get(0).get("SID");
        assertNotNull(sessionId, "SESSION_ID() should always report a value");
        return sessionId;
    }

    @Test
    @DisplayName("consecutive tool calls share one database session")
    void consecutiveToolCallsShareASession() {
        String first = sessionId();
        String second = sessionId();
        String third = sessionId();

        assertEquals(first, second, "tool calls on one MCP connection must reuse the database session");
        assertEquals(first, third);
    }

    @Test
    @DisplayName("a different tool on the same MCP connection uses the same session")
    void differentToolsShareTheSameSession() {
        String beforeMetadataCall = sessionId();
        McpTestClient.callTool("list_tables", Map.of());

        assertEquals(beforeMetadataCall, sessionId(),
                "metadata tools must not displace the session used by read_query");
    }

    @Test
    @DisplayName("database_info advertises that session state survives between calls")
    void databaseInfoAdvertisesAffinity() {
        Map<String, String> info = io.restassured.path.json.JsonPath
                .from(McpTestClient.callTool("database_info", Map.of()))
                .getMap("");

        assertEquals("true", info.get("session_state_persists_across_tool_calls"));
    }

    @Test
    @DisplayName("switching credentials on one MCP connection opens a fresh session")
    void switchingCredentialsOpensAFreshSession() {
        String asDefaultUser = sessionId();
        String asOtherUser = sessionId(OTHER_USER, OTHER_PASSWORD);
        String backAsDefaultUser = sessionId();

        assertNotEquals(asDefaultUser, asOtherUser,
                "a different identity must not be served the previous user's connection");
        assertNotEquals(asDefaultUser, backAsDefaultUser,
                "switching away closes the previous connection, so switching back opens a new one");
    }

    @Test
    @DisplayName("repeated calls on one MCP connection do not accumulate database connections")
    void oneConnectionPerMcpSession() {
        // Measured as a delta, not an absolute count: test classes sharing a profile also share the
        // Quarkus instance, so sessions belonging to other classes may still be retained here.
        sessionId();
        int retainedAfterFirstCall = sessions.retainedSessions();

        sessionId();
        sessionId();
        sessionId();

        assertEquals(retainedAfterFirstCall, sessions.retainedSessions(),
                "further calls on the same MCP connection must reuse its existing JDBC session");
        assertTrue(retainedAfterFirstCall >= 1);
    }
}
