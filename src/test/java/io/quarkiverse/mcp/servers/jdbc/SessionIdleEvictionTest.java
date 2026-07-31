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
import jakarta.inject.Inject;

/**
 * Covers the sweeper that closes retained connections once they go idle.
 *
 * <p>
 * A retained connection is a real database session, so failing to release it leaks server-side
 * resources - and releasing one that is still in use would be worse. The idle timeout is shortened
 * to a second here so the behaviour can be observed without a long wait.
 */
@QuarkusTest
@TestProfile(SessionIdleEvictionTest.ShortIdleTimeoutProfile.class)
class SessionIdleEvictionTest {

    private static final long IDLE_TIMEOUT_MILLIS = 1_000;

    public static class ShortIdleTimeoutProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("jdbc.session.idle-timeout", "PT1S");
        }
    }

    @Inject
    JdbcSessionManager sessions;

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
    @DisplayName("an idle connection is closed and the next call opens a fresh one")
    void idleConnectionIsClosedAndReopened() throws InterruptedException {
        String before = sessionId();
        assertEquals(before, sessionId(), "back-to-back calls should still share the session");

        int retainedWhileActive = sessions.retainedSessions();

        // Give the sweeper, which runs at half the idle timeout, a few passes to notice.
        Thread.sleep(IDLE_TIMEOUT_MILLIS * 4);

        assertEquals(retainedWhileActive - 1, sessions.retainedSessions(),
                "the idle connection should have been closed and dropped");
        assertNotEquals(before, sessionId(), "the next call must open a new database session");
    }
}
