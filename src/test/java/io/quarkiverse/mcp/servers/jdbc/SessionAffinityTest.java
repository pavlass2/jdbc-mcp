package io.quarkiverse.mcp.servers.jdbc;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Pins down the known "no session affinity" limitation documented in {@code docs/context_issue.md}.
 *
 * <p>
 * {@code MCPServerJDBC.getConnection()} opens a fresh JDBC connection per tool call and closes it
 * in a try-with-resources block, so any server-side session state - an Oracle VPD context set via
 * {@code DBMS_SESSION.SET_CONTEXT}, an {@code ALTER SESSION}, a session-scoped temporary table -
 * is gone by the time the next tool call runs.
 *
 * <p>
 * <b>This is a characterization test: it asserts the current, unwanted behaviour.</b> It exists so
 * that work on connection affinity has a failing signal to work against. When affinity is
 * implemented, this test is expected to fail - at which point invert the assertion rather than
 * deleting it, so the new guarantee stays covered.
 */
@QuarkusTest
class SessionAffinityTest {

    /** H2's session id changes with every physical connection, standing in for Oracle session state. */
    private static String sessionId() {
        List<Map<String, String>> rows = McpTestClient.readQuery("SELECT SESSION_ID() AS SID");
        String sessionId = rows.get(0).get("SID");
        assertNotNull(sessionId, "SESSION_ID() should always report a value");
        return sessionId;
    }

    @Test
    @DisplayName("consecutive tool calls do NOT share a database session (known limitation)")
    void consecutiveToolCallsDoNotShareASession() {
        String first = sessionId();
        String second = sessionId();

        assertNotEquals(first, second,
                """
                        Tool calls now share a database session. If that was intentional - i.e. connection \
                        affinity has been implemented for the Oracle VPD context work - invert this assertion \
                        and update docs/context_issue.md, because the limitation it documents no longer holds.\
                        """);
    }
}
