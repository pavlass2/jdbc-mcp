package io.quarkiverse.mcp.servers.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;

/**
 * End-to-end coverage of {@code run_script} over the MCP transport.
 *
 * <p>
 * The fixture database is H2, which has neither PL/SQL nor {@code DBMS_OUTPUT}, so what is verified
 * here is the execution loop: order, per-statement reporting, and what happens when one statement
 * fails. The Oracle-specific half - block terminators - is covered by
 * {@link SqlScriptSplitterTest}, and {@link DbmsOutput} degrades to no capture on a non-Oracle
 * database, which this test exercises implicitly by not seeing any {@code dbms_output} keys.
 *
 * <p>
 * Reuses {@code WriteToolsEnabledTest}'s profile so both classes share one Quarkus instance.
 */
@QuarkusTest
@TestProfile(WriteToolsEnabledTest.WriteEnabledProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RunScriptTest {

    /** The fixture database outlives every test class in the surefire JVM, so start from a known state. */
    @BeforeAll
    static void dropFixtureTable() throws SQLException {
        McpTestClient.newSession();
        try (Connection conn = DriverManager.getConnection(
                McpTestClient.H2_URL, McpTestClient.H2_USER, McpTestClient.H2_PASSWORD);
                Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS SCRIPT_DEMO");
        }
    }

    private static JsonPath runScript(String script) {
        return JsonPath.from(McpTestClient.callTool("run_script", Map.of("script", script)));
    }

    private static JsonPath runScript(String script, boolean continueOnError) {
        return JsonPath.from(McpTestClient.callTool("run_script",
                Map.of("script", script, "continue_on_error", continueOnError)));
    }

    @Test
    @Order(1)
    @DisplayName("run_script is advertised once enable.write.sql is set")
    void advertised() {
        List<String> tools = McpTestClient.toolNames();

        assertTrue(tools.contains("run_script"), "expected run_script, got: " + tools);
    }

    @Test
    @Order(2)
    @DisplayName("statements run in order and each is reported separately")
    void multiStatementScript() {
        JsonPath result = runScript("""
                CREATE TABLE SCRIPT_DEMO (ID INT PRIMARY KEY, NOTE VARCHAR(50));
                INSERT INTO SCRIPT_DEMO VALUES (1, 'first; still one value');
                INSERT INTO SCRIPT_DEMO VALUES (2, 'second');
                SELECT ID, NOTE FROM SCRIPT_DEMO ORDER BY ID;
                """);

        assertEquals(4, result.getInt("statements_total"));
        assertEquals(4, result.getInt("statements_executed"));
        assertFalse(result.getBoolean("stopped_on_error"));
        assertEquals(List.of("ok", "ok", "ok", "ok"), result.getList("results.status"));
        assertEquals(List.of(1, 2, 3, 4), result.getList("results.line"),
                "each statement should report the script line it started on");

        assertEquals(2, result.getInt("results[3].row_count"));
        assertEquals("first; still one value", result.getString("results[3].rows[0].NOTE"),
                "the semicolon inside the literal must not have split the INSERT");
        assertEquals("sql", result.getString("results[3].type"));
    }

    @Test
    @Order(3)
    @DisplayName("execution stops at the first failing statement by default")
    void stopsOnFirstError() {
        JsonPath result = runScript("""
                INSERT INTO SCRIPT_DEMO VALUES (3, 'third');
                INSERT INTO SCRIPT_DEMO VALUES (3, 'duplicate key');
                INSERT INTO SCRIPT_DEMO VALUES (4, 'never runs');
                """);

        assertEquals(3, result.getInt("statements_total"));
        assertEquals(2, result.getInt("statements_executed"));
        assertTrue(result.getBoolean("stopped_on_error"));
        assertEquals(1, result.getInt("not_run"));
        assertEquals(List.of("ok", "error"), result.getList("results.status"));
        assertTrue(result.getString("results[1].error") != null
                && !result.getString("results[1].error").isBlank(),
                "the database's own message is what makes the failure fixable");

        assertEquals(List.of(), McpTestClient.readQuery("SELECT ID FROM SCRIPT_DEMO WHERE ID = 4"),
                "the statement after the failure must not have run");
    }

    @Test
    @Order(4)
    @DisplayName("continue_on_error runs the remaining statements")
    void continuesOnErrorWhenAsked() {
        JsonPath result = runScript("""
                INSERT INTO SCRIPT_DEMO VALUES (5, 'fifth');
                INSERT INTO SCRIPT_DEMO VALUES (5, 'duplicate key');
                INSERT INTO SCRIPT_DEMO VALUES (6, 'sixth');
                """, true);

        assertEquals(3, result.getInt("statements_executed"));
        assertFalse(result.getBoolean("stopped_on_error"));
        assertEquals(List.of("ok", "error", "ok"), result.getList("results.status"));

        assertEquals(1, McpTestClient.readQuery("SELECT ID FROM SCRIPT_DEMO WHERE ID = 6").size(),
                "the statement after the failure should have run");
    }

    @Test
    @Order(5)
    @DisplayName("SQL*Plus commands are reported as skipped or rejected, not sent to the driver")
    void clientCommands() {
        JsonPath result = runScript("""
                SET SERVEROUTPUT ON
                SELECT COUNT(*) AS N FROM SCRIPT_DEMO;
                SPOOL /tmp/out.txt
                """);

        assertEquals(3, result.getInt("statements_total"));
        assertEquals(1, result.getInt("statements_executed"), "only the SELECT is a real statement");
        assertEquals(List.of("skipped", "ok", "rejected"), result.getList("results.status"));
        assertTrue(result.getString("results[0].note").contains("DBMS_OUTPUT"));
        assertTrue(result.getBoolean("stopped_on_error"), "a rejected command stops the script");
    }

    @Test
    @Order(6)
    @DisplayName("a single statement without a terminator still works")
    void singleStatementWithoutTerminator() {
        JsonPath result = runScript("SELECT COUNT(*) AS N FROM SCRIPT_DEMO");

        assertEquals(1, result.getInt("statements_total"));
        assertEquals("ok", result.getString("results[0].status"));
        assertEquals(1, result.getInt("results[0].row_count"));
    }

    @Test
    @Order(7)
    @DisplayName("a DDL or DML statement reports an update count rather than rows")
    void updateCountReported() {
        JsonPath result = runScript("UPDATE SCRIPT_DEMO SET NOTE = 'renamed' WHERE ID = 6;");

        assertEquals(1, result.getInt("results[0].update_count"));
        assertNull(result.get("results[0].rows"), "an update should not carry a rows array");
    }

    @Test
    @Order(8)
    @DisplayName("a script with nothing to run is an error, not an empty success")
    void emptyScriptRejected() {
        JsonPath response = McpTestClient.callToolRaw("run_script", Map.of("script", "-- just a comment\n"));

        assertTrue(McpTestClient.isFailure(response));
        assertTrue(McpTestClient.errorTextOf(response).contains("no statements"),
                "unexpected error: " + McpTestClient.errorTextOf(response));
    }

    @Test
    @Order(9)
    @DisplayName("session state set by one script is still there for the next call")
    void sessionStateSurvives() {
        // The point of running a script at all: an ALTER SESSION or a DBMS_SESSION.SET_CONTEXT in
        // statement one has to still apply in statement two, and in the next tool call. H2's local
        // temporary table is the closest equivalent it has.
        runScript("""
                CREATE LOCAL TEMPORARY TABLE SCRIPT_SCRATCH (V INT);
                INSERT INTO SCRIPT_SCRATCH VALUES (7);
                """);

        List<Map<String, String>> rows = McpTestClient.readQuery("SELECT V FROM SCRIPT_SCRATCH");

        assertEquals(1, rows.size(), "the temporary table should still exist on this session");
        assertEquals("7", rows.get(0).get("V"));
    }
}
