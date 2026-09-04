package io.quarkiverse.mcp.servers.jdbc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;

/**
 * Covers recovery from the failure that made this server look dead: one tool call gets stuck on a
 * long query, and because the session lock is held for the whole call, every later call on the same
 * MCP connection queues behind it - including trivial ones like {@code SELECT 1}.
 *
 * <p>
 * The statement timeout is switched off here on purpose, so that the only thing that can rescue the
 * second call is the contention handling in {@link JdbcSessionManager}.
 */
@QuarkusTest
@TestProfile(SessionContentionTest.NoQueryTimeoutProfile.class)
class SessionContentionTest {

    /** A query long enough to outlast the test, and cheap to cancel. */
    private static final String ENDLESS_QUERY =
            "SELECT COUNT(*) AS C FROM SYSTEM_RANGE(1, 9000000000) WHERE MOD(X, 7) = 0";

    public static class NoQueryTimeoutProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "jdbc.query.timeout", "0",
                    "jdbc.session.lock-timeout", "PT1S");
        }
    }

    @BeforeAll
    static void startSession() {
        McpTestClient.newSession();
    }

    @Test
    @DisplayName("a call stuck on a long query does not wedge the next call on the same session")
    void stuckCallDoesNotWedgeTheSession() throws Exception {
        // Establish the MCP session on this thread first: doing it concurrently would mint two
        // sessions, and then there would be no contention to observe.
        McpTestClient.readQuery("SELECT 1 AS ONE");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        try {
            Future<JsonPath> stuck = executor.submit(() -> {
                started.countDown();
                return McpTestClient.callToolRaw("read_query", Map.of("query", ENDLESS_QUERY));
            });

            assertTrue(started.await(5, TimeUnit.SECONDS), "the blocking call never started");
            // Let it get past the lock and into the driver before competing for the session.
            Thread.sleep(500);

            JsonPath response = McpTestClient.callToolRaw("read_query", Map.of("query", "SELECT 1 AS ONE"));

            assertFalse(McpTestClient.isFailure(response),
                    "the second call had to wait out the stuck one instead of cancelling it: "
                            + McpTestClient.errorTextOf(response));

            // The cancelled call must come back as a failure rather than hang for ever.
            assertTrue(McpTestClient.isFailure(stuck.get(30, TimeUnit.SECONDS)),
                    "the cancelled query should have been reported as a failed tool call");
        } finally {
            executor.shutdownNow();
        }
    }
}
