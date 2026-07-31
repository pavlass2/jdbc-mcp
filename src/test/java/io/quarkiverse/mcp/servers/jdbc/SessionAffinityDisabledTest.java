package io.quarkiverse.mcp.servers.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
 * Verifies the {@code jdbc.session.affinity=false} kill switch still yields the original
 * connection-per-call behaviour, for anyone who would rather not have the server hold database
 * sessions open between calls.
 */
@QuarkusTest
@TestProfile(SessionAffinityDisabledTest.AffinityDisabledProfile.class)
class SessionAffinityDisabledTest {

    public static class AffinityDisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("jdbc.session.affinity", "false");
        }
    }

    @BeforeAll
    static void startSession() {
        McpTestClient.newSession();
    }

    private static String sessionId() {
        List<Map<String, String>> rows = JsonPath
                .from(McpTestClient.callTool("read_query", Map.of("query", "SELECT SESSION_ID() AS SID")))
                .getList("");
        return rows.get(0).get("SID");
    }

    @Test
    @DisplayName("each tool call gets its own database session when affinity is off")
    void eachCallGetsItsOwnSession() {
        assertNotEquals(sessionId(), sessionId());
    }

    @Test
    @DisplayName("database_info reports that session state does not survive")
    void databaseInfoReportsNoAffinity() {
        Map<String, String> info = JsonPath
                .from(McpTestClient.callTool("database_info", Map.of()))
                .getMap("");

        assertEquals("false", info.get("session_state_persists_across_tool_calls"));
    }
}
