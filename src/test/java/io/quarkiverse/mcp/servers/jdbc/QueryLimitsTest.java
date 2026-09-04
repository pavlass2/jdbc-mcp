package io.quarkiverse.mcp.servers.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Covers the two bounds that keep one careless query from taking the server down with it: a
 * statement timeout, and a cap on the rows {@code read_query} will return.
 *
 * <p>
 * Both exist because the session lock is held for a whole tool call. A query that runs for minutes,
 * or a result set that takes minutes to materialise and serialise, blocks every later call on the
 * same MCP connection - and the client that asked for it has long since timed out and will never
 * read the answer.
 */
@QuarkusTest
@TestProfile(QueryLimitsTest.TightLimitsProfile.class)
class QueryLimitsTest {

    private static final int ROW_LIMIT = 10;

    public static class TightLimitsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "jdbc.query.max-rows", String.valueOf(ROW_LIMIT),
                    "jdbc.query.timeout", "1");
        }
    }

    @BeforeAll
    static void startSession() {
        McpTestClient.newSession();
    }

    @Test
    @DisplayName("a result within the limit is still returned as a plain array")
    void resultWithinLimitKeepsTheArrayShape() {
        List<Map<String, String>> rows = McpTestClient
                .readQuery("SELECT X FROM SYSTEM_RANGE(1, " + ROW_LIMIT + ")");

        assertEquals(ROW_LIMIT, rows.size());
        assertEquals("1", rows.get(0).get("X"));
    }

    @Test
    @DisplayName("an oversized result is cut to the limit and says so")
    void oversizedResultIsTruncatedAndFlagged() {
        JsonPath result = JsonPath.from(McpTestClient
                .callTool("read_query", Map.of("query", "SELECT X FROM SYSTEM_RANGE(1, 1000)")));

        assertTrue(result.getBoolean("rows_truncated"), "an over-limit result must announce that it was cut");
        assertEquals(ROW_LIMIT, result.getInt("row_limit"));
        assertEquals(ROW_LIMIT, result.getList("rows").size(),
                "the probe row read past the limit must not be handed to the caller");
    }

    @Test
    @DisplayName("a query that outruns the timeout is aborted and reported as a tool error")
    void longRunningQueryIsAborted() {
        JsonPath response = McpTestClient.callToolRaw("read_query",
                Map.of("query", "SELECT COUNT(*) AS C FROM SYSTEM_RANGE(1, 4000000000) WHERE MOD(X, 7) = 0"));

        assertTrue(McpTestClient.isFailure(response),
                "a query exceeding jdbc.query.timeout must fail rather than run to completion");
        assertTrue(McpTestClient.errorTextOf(response).contains("Query execution failed"),
                "unexpected error text: " + McpTestClient.errorTextOf(response));
    }

    @Test
    @DisplayName("the session is usable again once a timed-out query has been aborted")
    void sessionSurvivesAnAbortedQuery() {
        McpTestClient.callToolRaw("read_query",
                Map.of("query", "SELECT COUNT(*) AS C FROM SYSTEM_RANGE(1, 4000000000) WHERE MOD(X, 7) = 0"));

        JsonPath response = McpTestClient.callToolRaw("read_query", Map.of("query", "SELECT 1 AS ONE"));

        assertFalse(McpTestClient.isFailure(response),
                "the aborted query must not leave the session wedged: " + McpTestClient.errorTextOf(response));
    }
}
