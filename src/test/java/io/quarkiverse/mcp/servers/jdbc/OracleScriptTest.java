package io.quarkiverse.mcp.servers.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.oracle.OracleContainer;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;

/**
 * The half of {@code run_script} that H2 cannot cover: real PL/SQL blocks, the {@code /} terminator
 * as Oracle's own parser sees it, and {@code DBMS_OUTPUT} capture.
 *
 * <p>
 * Everything here is a claim the rest of the suite has to take on faith - {@link SqlScriptSplitterTest}
 * proves a block is split out correctly, but only Oracle can say whether what came out is something
 * it will actually run.
 *
 * <p>
 * <b>Opt-in.</b> The container image is a large pull and adds minutes to the build, so this class is
 * skipped unless {@code -Doracle.tests=true} is passed (the {@code oracle.tests} Maven property,
 * forwarded to the test JVM by surefire). CI runs it as a separate {@code oracle-tests} job. A
 * Docker daemon is required when it is enabled.
 *
 * <p>
 * Reuses {@code WriteToolsEnabledTest}'s profile so it shares a Quarkus instance with the other
 * write-enabled classes. Credentials reach the server the same way a real client's would - as
 * {@code x-jdbc-*} request headers - rather than through the {@code jdbc.*} configuration.
 */
@QuarkusTest
@TestProfile(WriteToolsEnabledTest.WriteEnabledProfile.class)
@EnabledIfSystemProperty(named = "oracle.tests", matches = "true",
        disabledReason = "Oracle container tests are opt-in; run with -Doracle.tests=true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OracleScriptTest {

    /**
     * Started by hand rather than through {@code @Testcontainers}/{@code @Container}.
     *
     * <p>
     * That extension starts the container from its own {@code beforeAll} callback, but
     * {@code @QuarkusTest} reloads the test class in the Quarkus classloader - so the copy running
     * the tests holds a second, never-started instance and every call fails with "Mapped port can
     * only be obtained after the container is started". Starting it from {@code @BeforeAll} keeps
     * the container and the tests on the same copy of the class whichever loader wins, and means
     * nothing starts at all when the condition above disables the class.
     */
    static OracleContainer oracle;

    @BeforeAll
    static void startOracleAndCreateFixtures() throws SQLException {
        McpTestClient.newSession();
        // Oracle Free rather than XE: it is the currently maintained image, and the faststart
        // variant boots a pre-created database in about a minute instead of building one.
        oracle = new OracleContainer("gvenzl/oracle-free:slim-faststart")
                .withUsername("jdbcmcp")
                .withPassword("jdbcmcp")
                .withStartupTimeout(Duration.ofMinutes(5));
        oracle.start();

        try (Connection conn = DriverManager.getConnection(
                oracle.getJdbcUrl(), oracle.getUsername(), oracle.getPassword());
                Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE SCRIPT_ITEMS (ID NUMBER PRIMARY KEY, NAME VARCHAR2(50))");
        }
    }

    @AfterAll
    static void stopOracle() {
        if (oracle != null) {
            oracle.stop();
        }
    }

    private static Map<String, String> headers() {
        return Map.of(
                "x-jdbc-url", oracle.getJdbcUrl(),
                "x-jdbc-user", oracle.getUsername(),
                "x-jdbc-password", oracle.getPassword());
    }

    private static JsonPath runScript(String script) {
        return JsonPath.from(McpTestClient.callTool("run_script", Map.of("script", script), headers()));
    }

    private static List<Map<String, String>> readQuery(String sql) {
        return JsonPath.from(McpTestClient.callTool("read_query", Map.of("query", sql), headers())).getList("");
    }

    @Test
    @Order(1)
    @DisplayName("a trailing semicolon on plain SQL is stripped rather than sent to Oracle")
    void trailingSemicolonIsStripped() {
        // Oracle answers ORA-00911 "invalid character" if the semicolon survives, which is the
        // single most likely way this whole feature breaks.
        JsonPath result = runScript("SELECT 1 AS N FROM DUAL;");

        assertEquals("ok", result.getString("results[0].status"),
                "unexpected error: " + result.getString("results[0].error"));
        assertEquals("1", result.getString("results[0].rows[0].N"));
    }

    @Test
    @Order(2)
    @DisplayName("an anonymous block runs and its DBMS_OUTPUT comes back")
    void anonymousBlockWithOutput() {
        JsonPath result = runScript("""
                BEGIN
                  DBMS_OUTPUT.PUT_LINE('hello from plsql');
                  DBMS_OUTPUT.PUT_LINE('second line');
                END;
                /
                """);

        assertEquals("ok", result.getString("results[0].status"),
                "unexpected error: " + result.getString("results[0].error"));
        assertEquals("plsql_block", result.getString("results[0].type"));
        assertEquals(List.of("hello from plsql", "second line"),
                result.getList("results[0].dbms_output"),
                "SET SERVEROUTPUT ON should not be needed for this to arrive");
    }

    @Test
    @Order(3)
    @DisplayName("a stored procedure is created, called and its effect queried in one script")
    void createProcedureCallAndQuery() {
        // The full round trip: the slash has to close the procedure body without swallowing the
        // block after it, and Oracle has to accept both as it received them.
        JsonPath result = runScript("""
                CREATE OR REPLACE PROCEDURE add_item(p_id NUMBER, p_name VARCHAR2) AS
                BEGIN
                  INSERT INTO SCRIPT_ITEMS VALUES (p_id, p_name);
                END;
                /
                BEGIN
                  add_item(1, 'widget');
                  COMMIT;
                END;
                /
                SELECT NAME FROM SCRIPT_ITEMS WHERE ID = 1;
                """);

        assertEquals(3, result.getInt("statements_total"));
        assertEquals(List.of("ok", "ok", "ok"), result.getList("results.status"),
                "one of them failed: " + result.getList("results.error"));
        assertEquals(List.of("plsql_block", "plsql_block", "sql"), result.getList("results.type"));
        assertEquals("widget", result.getString("results[2].rows[0].NAME"));
    }

    @Test
    @Order(4)
    @DisplayName("output printed before a failure is still returned with the error")
    void outputSurvivesAFailure() {
        JsonPath result = runScript("""
                BEGIN
                  DBMS_OUTPUT.PUT_LINE('before the failure');
                  RAISE_APPLICATION_ERROR(-20001, 'deliberate');
                END;
                /
                """);

        assertEquals("error", result.getString("results[0].status"));
        assertTrue(result.getString("results[0].error").contains("ORA-20001"),
                "the database's own message should come through: " + result.getString("results[0].error"));
        assertEquals(List.of("before the failure"), result.getList("results[0].dbms_output"),
                "lines printed up to the failure are the most useful part of the response");
    }

    @Test
    @Order(5)
    @DisplayName("SET SERVEROUTPUT ON is skipped and output still arrives")
    void serveroutputCommandIsRedundant() {
        JsonPath result = runScript("""
                SET SERVEROUTPUT ON
                BEGIN
                  DBMS_OUTPUT.PUT_LINE('still captured');
                END;
                /
                """);

        assertEquals("skipped", result.getString("results[0].status"));
        assertEquals("ok", result.getString("results[1].status"),
                "unexpected error: " + result.getString("results[1].error"));
        assertEquals(List.of("still captured"), result.getList("results[1].dbms_output"));
    }

    @Test
    @Order(6)
    @DisplayName("q'[...]' literals reach Oracle intact")
    void alternativeQuotingSurvivesSplitting() {
        // Oracle-only syntax whose entire purpose is to contain the quotes and semicolons the
        // splitter is looking for.
        JsonPath result = runScript("""
                INSERT INTO SCRIPT_ITEMS VALUES (2, q'[it's; fine]');
                SELECT NAME FROM SCRIPT_ITEMS WHERE ID = 2;
                """);

        assertEquals(List.of("ok", "ok"), result.getList("results.status"),
                "one of them failed: " + result.getList("results.error"));
        assertEquals("it's; fine", result.getString("results[1].rows[0].NAME"));
    }

    @Test
    @Order(7)
    @DisplayName("session state set by a block is still there in the next tool call")
    void sessionStateSurvivesAcrossToolCalls() {
        // This is docs/context_issue.md on a real Oracle: the model sets something up on the
        // session in one call and reads it back in another. Before session affinity the second
        // call landed on a fresh connection and saw nothing. CLIENT_IDENTIFIER stands in for a VPD
        // context because it needs no extra privileges to set.
        runScript("BEGIN DBMS_SESSION.SET_IDENTIFIER('org-42'); END;\n/");

        List<Map<String, String>> rows =
                readQuery("SELECT SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER') AS ID FROM DUAL");

        assertEquals(1, rows.size());
        assertEquals("org-42", rows.get(0).get("ID"),
                "the second tool call must have run on the same database session");
    }

    @Test
    @Order(8)
    @DisplayName("a failing statement stops the script before the ones after it")
    void stopsOnErrorAgainstOracle() {
        JsonPath result = runScript("""
                INSERT INTO SCRIPT_ITEMS VALUES (3, 'third');
                INSERT INTO SCRIPT_ITEMS VALUES (3, 'duplicate key');
                INSERT INTO SCRIPT_ITEMS VALUES (4, 'never runs');
                """);

        assertEquals(2, result.getInt("statements_executed"));
        assertTrue(result.getBoolean("stopped_on_error"));
        assertTrue(result.getString("results[1].error").contains("ORA-00001"),
                "expected a unique constraint violation: " + result.getString("results[1].error"));
        assertEquals(List.of(), readQuery("SELECT ID FROM SCRIPT_ITEMS WHERE ID = 4"));
    }

    @Test
    @Order(9)
    @DisplayName("a PL/SQL block left unterminated by a slash is reported, not silently merged")
    void missingSlashIsVisible() {
        // The one failure mode the description warns about: without the slash the SELECT becomes
        // part of the block, and Oracle rejects the result. What matters is that the model gets a
        // real compiler message rather than a silent wrong answer.
        JsonPath result = runScript("""
                BEGIN
                  NULL;
                END;
                SELECT 1 AS N FROM DUAL;
                """);

        assertEquals(1, result.getInt("statements_total"), "the SELECT should have been swallowed by the block");
        assertEquals("error", result.getString("results[0].status"));
        assertFalse(result.getString("results[0].error").isBlank());
    }
}
