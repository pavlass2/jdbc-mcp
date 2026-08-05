package io.quarkiverse.mcp.servers.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.servers.jdbc.SqlScriptSplitter.Kind;
import io.quarkiverse.mcp.servers.jdbc.SqlScriptSplitter.Statement;

/**
 * Plain JUnit, no database and no Quarkus boot - splitting is a pure text problem.
 *
 * <p>
 * This carries most of the weight for {@code run_script}: the test database is H2, which has no
 * PL/SQL at all, so the terminator rules that make Oracle blocks work can only be verified here.
 */
class SqlScriptSplitterTest {

    @Nested
    @DisplayName("plain SQL")
    class PlainSql {

        @Test
        @DisplayName("semicolons separate statements and are dropped")
        void semicolonSeparatesStatements() {
            List<Statement> statements = SqlScriptSplitter.split("""
                    CREATE TABLE T (A INT);
                    INSERT INTO T VALUES (1);
                    """);

            assertEquals(2, statements.size());
            assertEquals("CREATE TABLE T (A INT)", statements.get(0).sql());
            assertEquals(Kind.SQL, statements.get(0).kind());
            assertEquals(1, statements.get(0).line());
            assertEquals("INSERT INTO T VALUES (1)", statements.get(1).sql());
            assertEquals(2, statements.get(1).line());
        }

        @Test
        @DisplayName("a trailing statement with no terminator still runs")
        void unterminatedTrailingStatement() {
            // The overwhelmingly common single-statement call, which SQL*Plus itself would leave
            // sitting in the buffer.
            List<Statement> statements = SqlScriptSplitter.split("SELECT 1 FROM DUAL");

            assertEquals(1, statements.size());
            assertEquals("SELECT 1 FROM DUAL", statements.get(0).sql());
        }

        @Test
        @DisplayName("a lone slash also terminates plain SQL")
        void slashTerminatesPlainSql() {
            List<Statement> statements = SqlScriptSplitter.split("CREATE TABLE T (A INT)\n/\n");

            assertEquals(1, statements.size());
            assertEquals("CREATE TABLE T (A INT)", statements.get(0).sql());
            assertEquals(Kind.SQL, statements.get(0).kind());
        }

        @Test
        @DisplayName("a slash after an already terminated statement does not re-run it")
        void slashAfterSemicolonIsNotASecondStatement() {
            List<Statement> statements = SqlScriptSplitter.split("SELECT 1 FROM DUAL;\n/\n");

            assertEquals(1, statements.size());
        }

        @Test
        @DisplayName("an empty or comment-only script yields nothing to run")
        void nothingToRun() {
            assertEquals(List.of(), SqlScriptSplitter.split(""));
            assertEquals(List.of(), SqlScriptSplitter.split("   \n\n  "));
            assertEquals(List.of(), SqlScriptSplitter.split(null));
            assertEquals(List.of(), SqlScriptSplitter.split("-- nothing here\n/* nor here */\n"));
        }

        @Test
        @DisplayName("a comment before the last statement is kept, one after it is dropped")
        void trailingCommentIsNotAStatement() {
            List<Statement> statements = SqlScriptSplitter.split("SELECT 1;\n-- done\n");

            assertEquals(1, statements.size());
            assertEquals("SELECT 1", statements.get(0).sql());
        }
    }

    @Nested
    @DisplayName("quoting and comments hide terminators")
    class Quoting {

        @Test
        @DisplayName("a semicolon inside a string literal does not split")
        void semicolonInStringLiteral() {
            List<Statement> statements = SqlScriptSplitter.split("INSERT INTO T VALUES ('a;b');");

            assertEquals(1, statements.size());
            assertEquals("INSERT INTO T VALUES ('a;b')", statements.get(0).sql());
        }

        @Test
        @DisplayName("a doubled quote escapes rather than closes the literal")
        void escapedQuoteInStringLiteral() {
            List<Statement> statements = SqlScriptSplitter.split("INSERT INTO T VALUES ('it''s; fine');");

            assertEquals(1, statements.size());
            assertEquals("INSERT INTO T VALUES ('it''s; fine')", statements.get(0).sql());
        }

        @Test
        @DisplayName("q'[...]' literals may contain quotes and semicolons")
        void alternativeQuoting() {
            List<Statement> statements = SqlScriptSplitter.split("INSERT INTO T VALUES (q'[it's; here]');");

            assertEquals(1, statements.size());
            assertEquals("INSERT INTO T VALUES (q'[it's; here]')", statements.get(0).sql());
        }

        @Test
        @DisplayName("a semicolon inside a quoted identifier does not split")
        void semicolonInQuotedIdentifier() {
            List<Statement> statements = SqlScriptSplitter.split("SELECT \"odd;name\" FROM T;");

            assertEquals(1, statements.size());
            assertEquals("SELECT \"odd;name\" FROM T", statements.get(0).sql());
        }

        @Test
        @DisplayName("semicolons inside comments do not split")
        void semicolonInComments() {
            assertEquals(1, SqlScriptSplitter.split("-- drop this; really\nSELECT 1;").size());
            assertEquals(1, SqlScriptSplitter.split("/* a; b */ SELECT 1;").size());
        }

        @Test
        @DisplayName("a division slash mid-line is not a terminator")
        void divisionIsNotATerminator() {
            List<Statement> statements = SqlScriptSplitter.split("SELECT a / b FROM t;");

            assertEquals(1, statements.size());
            assertEquals("SELECT a / b FROM t", statements.get(0).sql());
        }
    }

    @Nested
    @DisplayName("PL/SQL blocks end at a slash, not at a semicolon")
    class PlsqlBlocks {

        @Test
        @DisplayName("an anonymous block keeps its own semicolons")
        void anonymousBlock() {
            List<Statement> statements = SqlScriptSplitter.split("""
                    BEGIN
                      INSERT INTO T VALUES (1);
                      COMMIT;
                    END;
                    /
                    SELECT 1;
                    """);

            assertEquals(2, statements.size());
            assertEquals(Kind.PLSQL_BLOCK, statements.get(0).kind());
            assertTrue(statements.get(0).sql().endsWith("END;"),
                    "the block's final semicolon is part of it: " + statements.get(0).sql());
            assertTrue(statements.get(0).sql().contains("COMMIT;"));
            assertEquals(Kind.SQL, statements.get(1).kind());
            assertEquals("SELECT 1", statements.get(1).sql());
            assertEquals(6, statements.get(1).line(), "the line number should survive the block");
        }

        @Test
        @DisplayName("a DECLARE section does not end the block at its first semicolon")
        void declareSection() {
            List<Statement> statements = SqlScriptSplitter.split("""
                    DECLARE
                      v NUMBER;
                    BEGIN
                      v := 1;
                    END;
                    """);

            assertEquals(1, statements.size(), "should be one block, not three fragments");
            assertEquals(Kind.PLSQL_BLOCK, statements.get(0).kind());
        }

        @Test
        @DisplayName("stored program units are blocks too")
        void storedProgramUnits() {
            for (String header : List.of(
                    "CREATE PROCEDURE p AS",
                    "CREATE OR REPLACE PROCEDURE p AS",
                    "CREATE OR REPLACE FUNCTION f RETURN NUMBER AS",
                    "CREATE OR REPLACE PACKAGE BODY pkg AS",
                    "CREATE OR REPLACE NONEDITIONABLE TRIGGER trg BEFORE INSERT ON t FOR EACH ROW",
                    "CREATE OR REPLACE TYPE BODY ty AS")) {
                List<Statement> statements = SqlScriptSplitter.split(header + "\nBEGIN\n  NULL;\nEND;\n/\n");

                assertEquals(1, statements.size(), "should be one unit: " + header);
                assertEquals(Kind.PLSQL_BLOCK, statements.get(0).kind(), header);
            }
        }

        @Test
        @DisplayName("CREATE statements that are not PL/SQL still end at a semicolon")
        void nonPlsqlCreateStatements() {
            for (String sql : List.of(
                    "CREATE OR REPLACE VIEW v AS SELECT 1 FROM DUAL",
                    "CREATE TABLE t (a INT)",
                    "CREATE INDEX i ON t (a)",
                    "CREATE OR REPLACE SYNONYM s FOR t")) {
                List<Statement> statements = SqlScriptSplitter.split(sql + ";\nSELECT 1;");

                assertEquals(2, statements.size(), "should not have swallowed the next statement: " + sql);
                assertEquals(Kind.SQL, statements.get(0).kind(), sql);
            }
        }

        @Test
        @DisplayName("a block at the end of the script runs even without the closing slash")
        void blockWithoutClosingSlash() {
            List<Statement> statements = SqlScriptSplitter.split("BEGIN\n  NULL;\nEND;");

            assertEquals(1, statements.size());
            assertEquals(Kind.PLSQL_BLOCK, statements.get(0).kind());
        }
    }

    @Nested
    @DisplayName("SQL*Plus client commands")
    class ClientCommands {

        @Test
        @DisplayName("a command line needs no terminator and does not merge into the next statement")
        void commandIsItsOwnLine() {
            List<Statement> statements = SqlScriptSplitter.split("""
                    SET SERVEROUTPUT ON
                    BEGIN NULL; END;
                    /
                    """);

            assertEquals(2, statements.size());
            assertEquals(Kind.IGNORED, statements.get(0).kind());
            assertTrue(statements.get(0).note().contains("DBMS_OUTPUT"),
                    "the note should say why it is unnecessary: " + statements.get(0).note());
            assertEquals(Kind.PLSQL_BLOCK, statements.get(1).kind());
        }

        @Test
        @DisplayName("formatting commands are ignored with a note")
        void ignoredCommands() {
            for (String command : List.of("SET LINESIZE 200", "PROMPT loading...", "COLUMN name FORMAT A30",
                    "COL name FORMAT A30", "WHENEVER SQLERROR EXIT", "TTITLE OFF", "CLEAR SCREEN",
                    "REM a remark", "SHOW ERRORS")) {
                List<Statement> statements = SqlScriptSplitter.split(command + "\nSELECT 1;");

                assertEquals(2, statements.size(), command);
                assertEquals(Kind.IGNORED, statements.get(0).kind(), command);
                assertTrue(statements.get(0).note() != null && !statements.get(0).note().isBlank(), command);
            }
        }

        @Test
        @DisplayName("file access is rejected rather than quietly skipped")
        void rejectedCommands() {
            for (String command : List.of("@setup.sql", "@@relative.sql", "SPOOL /tmp/out.txt")) {
                List<Statement> statements = SqlScriptSplitter.split(command + "\nSELECT 1;");

                assertEquals(2, statements.size(), command);
                assertEquals(Kind.REJECTED, statements.get(0).kind(), command);
            }
        }

        @Test
        @DisplayName("SET and SHOW forms that are real SQL elsewhere are left alone")
        void dialectStatementsAreNotMistakenForCommands() {
            // PostgreSQL, MySQL and SQL Server all have genuine SET and SHOW statements; only the
            // known SQL*Plus option names may be swallowed.
            for (String sql : List.of("SET search_path TO app", "SET autocommit = 0",
                    "SET TRANSACTION ISOLATION LEVEL READ COMMITTED", "SHOW TABLES", "SHOW DATABASES")) {
                List<Statement> statements = SqlScriptSplitter.split(sql + ";");

                assertEquals(1, statements.size(), sql);
                assertEquals(Kind.SQL, statements.get(0).kind(), sql);
                assertEquals(sql, statements.get(0).sql());
            }
        }

        @Test
        @DisplayName("a command is only recognised at the start of a line")
        void notRecognisedMidStatement() {
            List<Statement> statements = SqlScriptSplitter.split("SELECT 1 FROM t WHERE col = 'SET LINESIZE 200';");

            assertEquals(1, statements.size());
            assertEquals(Kind.SQL, statements.get(0).kind());
        }
    }
}
