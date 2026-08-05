package io.quarkiverse.mcp.servers.jdbc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Splits a multi-statement script into the individual statements a JDBC driver can execute.
 *
 * <p>
 * JDBC executes exactly one statement per call - Oracle in particular rejects both a trailing
 * semicolon on plain SQL ({@code ORA-00911}) and any attempt to send several statements at once. A
 * script written for SQL*Plus or SQL Developer therefore has to be taken apart first, following the
 * same terminator rules those tools use:
 *
 * <ul>
 * <li>plain SQL ends at a {@code ;}, which is dropped;</li>
 * <li>a PL/SQL block is full of semicolons of its own, so it ends only at a line containing nothing
 * but {@code /}. Its semicolons - including the one after {@code END} - are kept.</li>
 * </ul>
 *
 * <p>
 * Semicolons and slashes inside string literals, quoted identifiers, {@code q'[...]'} literals,
 * {@code --} comments and {@code /* *&#47;} comments do not terminate anything.
 *
 * <h2>SQL*Plus client commands</h2>
 *
 * {@code SET SERVEROUTPUT ON}, {@code SPOOL}, {@code @file} and friends are features of the SQL*Plus
 * client; they never reach the database, and passing them to a driver produces a baffling syntax
 * error. They are recognised here so the caller can report something useful instead - either
 * {@link Kind#IGNORED} for the ones that are simply irrelevant to this server, or
 * {@link Kind#REJECTED} for the ones that would have done something we will not do.
 *
 * <p>
 * Only commands that cannot be valid SQL in any supported dialect are recognised. {@code SET} in
 * particular is matched only against the known SQL*Plus option names, so PostgreSQL's
 * {@code SET search_path} or MySQL's {@code SET autocommit} still pass through to the driver.
 */
public final class SqlScriptSplitter {

    /** What the caller should do with a chunk of the script. */
    public enum Kind {
        /** Ordinary SQL. Execute it. */
        SQL,
        /** A PL/SQL block, terminated by a lone {@code /}. Execute it; it keeps its semicolons. */
        PLSQL_BLOCK,
        /** A SQL*Plus client command that is a no-op here. Skip it and report the note. */
        IGNORED,
        /** A SQL*Plus client command this server declines to run. Report the note and stop. */
        REJECTED
    }

    /**
     * One chunk of the script.
     *
     * @param sql the statement text, terminator removed
     * @param line the 1-based line of the script the chunk starts on, so an error can be pointed at
     * @param kind what to do with it
     * @param note why it is being skipped or rejected, {@code null} for executable statements
     */
    public record Statement(String sql, int line, Kind kind, String note) {

        public boolean executable() {
            return kind == Kind.SQL || kind == Kind.PLSQL_BLOCK;
        }
    }

    /**
     * Keywords that start something PL/SQL, whose body is delimited by {@code /} rather than by the
     * first semicolon. {@code PACKAGE BODY} and {@code TYPE BODY} need no separate entry - the
     * decision is already made by the word before {@code BODY}.
     */
    private static final Set<String> PLSQL_OBJECTS = Set.of(
            "PROCEDURE", "FUNCTION", "PACKAGE", "TRIGGER", "TYPE", "LIBRARY");

    /** Option names that make a {@code SET} line SQL*Plus's rather than the database's. */
    private static final Set<String> SQLPLUS_SET_OPTIONS = Set.of(
            "SERVEROUTPUT", "DEFINE", "ECHO", "FEEDBACK", "HEADING", "LINESIZE", "PAGESIZE",
            "VERIFY", "TERMOUT", "TRIMSPOOL", "TRIMOUT", "TIMING", "SQLBLANKLINES", "LONG",
            "LONGCHUNKSIZE", "WRAP", "COLSEP", "NEWPAGE", "ESCAPE", "MARKUP", "AUTOPRINT",
            "NUMWIDTH", "NUMFORMAT", "PAUSE", "ARRAYSIZE", "AUTOTRACE", "SQLPROMPT", "CONCAT",
            "RECSEP", "UNDERLINE", "FLUSH", "SHOWMODE", "BLOCKTERMINATOR", "SQLTERMINATOR");

    /** Formatting-only SQL*Plus commands, none of which is valid SQL in any supported dialect. */
    private static final Set<String> IGNORABLE_COMMANDS = Set.of(
            "PROMPT", "WHENEVER", "COLUMN", "COL", "TTITLE", "BTITLE", "CLEAR", "REM", "REMARK",
            "SET", "SHOW");

    private SqlScriptSplitter() {
    }

    public static List<Statement> split(String script) {
        List<Statement> statements = new ArrayList<>();
        if (script == null || script.isBlank()) {
            return statements;
        }

        StringBuilder buffer = new StringBuilder();
        int length = script.length();
        int line = 1;
        // Line the current statement started on, -1 while nothing has been accumulated yet. Assigned
        // lazily on the first non-blank character so that blank lines between statements are
        // attributed to neither.
        int statementLine = -1;
        // True while nothing but whitespace has been seen since the last newline. SQL*Plus only
        // treats '/' as a terminator, and only recognises its own commands, at the start of a line.
        boolean atLineStart = true;

        int i = 0;
        while (i < length) {
            char c = script.charAt(i);

            if (c == '\n') {
                buffer.append(c);
                line++;
                atLineStart = true;
                i++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                buffer.append(c);
                i++;
                continue;
            }

            // A SQL*Plus command occupies a whole line and needs no terminator, so it can only be
            // recognised before any statement has started accumulating.
            if (atLineStart && buffer.toString().isBlank()) {
                int endOfLine = endOfLine(script, i);
                String candidate = script.substring(i, endOfLine);
                Directive directive = directiveOf(candidate);
                if (directive != null) {
                    statements.add(new Statement(candidate.strip(), line, directive.kind(), directive.note()));
                    buffer.setLength(0);
                    statementLine = -1;
                    i = endOfLine;
                    continue;
                }
            }

            if (statementLine < 0) {
                statementLine = line;
            }

            if (c == '-' && i + 1 < length && script.charAt(i + 1) == '-') {
                while (i < length && script.charAt(i) != '\n') {
                    buffer.append(script.charAt(i));
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < length && script.charAt(i + 1) == '*') {
                buffer.append("/*");
                i += 2;
                while (i < length && !(script.charAt(i) == '*' && i + 1 < length && script.charAt(i + 1) == '/')) {
                    if (script.charAt(i) == '\n') {
                        line++;
                    }
                    buffer.append(script.charAt(i));
                    i++;
                }
                if (i < length) {
                    buffer.append("*/");
                    i += 2;
                }
                atLineStart = false;
                continue;
            }

            // A '/' alone on its line runs the buffer, whether or not it is a PL/SQL block. Anywhere
            // else on the line it is division.
            if (c == '/' && atLineStart && isBlankTo(script, i + 1, endOfLine(script, i))) {
                flush(statements, buffer, statementLine);
                statementLine = -1;
                i = endOfLine(script, i);
                continue;
            }

            if (c == '\'') {
                i = copyDelimited(script, i, '\'', buffer);
                atLineStart = false;
                continue;
            }
            if (c == '"') {
                i = copyDelimited(script, i, '"', buffer);
                atLineStart = false;
                continue;
            }
            if ((c == 'q' || c == 'Q') && i + 1 < length && script.charAt(i + 1) == '\''
                    && !isWordCharacter(previousNonWhitespace(buffer))) {
                i = copyAlternativelyQuoted(script, i, buffer);
                atLineStart = false;
                continue;
            }

            if (c == ';') {
                if (startsPlsqlBlock(buffer)) {
                    // Inside a block the semicolon separates the block's own statements; only the
                    // slash line ends it.
                    buffer.append(c);
                    atLineStart = false;
                    i++;
                    continue;
                }
                flush(statements, buffer, statementLine);
                statementLine = -1;
                atLineStart = false;
                i++;
                continue;
            }

            buffer.append(c);
            atLineStart = false;
            i++;
        }

        // A script whose last statement has no terminator at all is by far the most common way a
        // model will call this, so run it rather than silently dropping it.
        flush(statements, buffer, statementLine);
        return statements;
    }

    /** Appends whatever has accumulated as a statement, unless it is only whitespace and comments. */
    private static void flush(List<Statement> statements, StringBuilder buffer, int line) {
        String sql = buffer.toString().strip();
        buffer.setLength(0);
        if (sql.isEmpty() || skipCommentsAndWhitespace(sql, 0) >= sql.length()) {
            return;
        }
        statements.add(new Statement(sql, Math.max(line, 1),
                startsPlsqlBlock(sql) ? Kind.PLSQL_BLOCK : Kind.SQL, null));
    }

    /**
     * Copies a {@code '...'} literal or a {@code "..."} identifier, including its quotes, treating a
     * doubled delimiter as an escaped one.
     *
     * @return the index just past the closing delimiter
     */
    private static int copyDelimited(String script, int start, char delimiter, StringBuilder buffer) {
        buffer.append(delimiter);
        int i = start + 1;
        while (i < script.length()) {
            char c = script.charAt(i);
            if (c == delimiter) {
                if (i + 1 < script.length() && script.charAt(i + 1) == delimiter) {
                    buffer.append(delimiter).append(delimiter);
                    i += 2;
                    continue;
                }
                buffer.append(delimiter);
                return i + 1;
            }
            buffer.append(c);
            i++;
        }
        return i;
    }

    /**
     * Copies an Oracle alternative-quoting literal - {@code q'[...]'}, {@code q'{...}'},
     * {@code q'<...>'}, {@code q'(...)'} or {@code q'!...!'} - whose whole point is to contain
     * unescaped quotes and semicolons.
     */
    private static int copyAlternativelyQuoted(String script, int start, StringBuilder buffer) {
        // start is 'q', start+1 is the quote, start+2 is the opening delimiter.
        if (start + 2 >= script.length()) {
            buffer.append(script, start, script.length());
            return script.length();
        }
        char opening = script.charAt(start + 2);
        char closing = switch (opening) {
            case '[' -> ']';
            case '{' -> '}';
            case '<' -> '>';
            case '(' -> ')';
            default -> opening;
        };
        buffer.append(script, start, start + 3);
        int i = start + 3;
        while (i < script.length()) {
            if (script.charAt(i) == closing && i + 1 < script.length() && script.charAt(i + 1) == '\'') {
                buffer.append(closing).append('\'');
                return i + 2;
            }
            buffer.append(script.charAt(i));
            i++;
        }
        return i;
    }

    /**
     * Whether the statement accumulated so far is a PL/SQL block, decided from its opening keywords
     * alone so that the very first semicolon - which in {@code DECLARE v NUMBER;} arrives long
     * before the end of the block - does not cut it short.
     */
    static boolean startsPlsqlBlock(CharSequence statement) {
        List<String> words = leadingWords(statement.toString(), 5);
        if (words.isEmpty()) {
            return false;
        }
        String first = words.get(0);
        if (first.equals("DECLARE") || first.equals("BEGIN")) {
            return true;
        }
        if (!first.equals("CREATE")) {
            return false;
        }
        int at = 1;
        if (words.size() > at + 1 && words.get(at).equals("OR") && words.get(at + 1).equals("REPLACE")) {
            at += 2;
        }
        if (words.size() > at && (words.get(at).equals("EDITIONABLE") || words.get(at).equals("NONEDITIONABLE"))) {
            at++;
        }
        return words.size() > at && PLSQL_OBJECTS.contains(words.get(at));
    }

    /** The first {@code limit} uppercased words of a statement, skipping any leading comments. */
    private static List<String> leadingWords(String statement, int limit) {
        List<String> words = new ArrayList<>();
        int i = skipCommentsAndWhitespace(statement, 0);
        while (i < statement.length() && words.size() < limit) {
            char c = statement.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (!isWordCharacter(c)) {
                // A quote, parenthesis or operator: no keyword can follow that is still part of the
                // opening phrase we are matching.
                break;
            }
            int start = i;
            while (i < statement.length() && isWordCharacter(statement.charAt(i))) {
                i++;
            }
            words.add(statement.substring(start, i).toUpperCase(Locale.ROOT));
        }
        return words;
    }

    private static int skipCommentsAndWhitespace(String text, int from) {
        int i = from;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < text.length() && text.charAt(i + 1) == '-') {
                while (i < text.length() && text.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? text.length() : end + 2;
            } else {
                return i;
            }
        }
        return i;
    }

    /** A SQL*Plus client command, or {@code null} if the line is (or could be) real SQL. */
    private static Directive directiveOf(String rawLine) {
        String trimmed = rawLine.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("@")) {
            return new Directive(Kind.REJECTED, "Running a script file from the server's filesystem is not"
                    + " supported. Inline the statements into the script argument instead.");
        }
        List<String> words = leadingWords(trimmed, 2);
        if (words.isEmpty()) {
            return null;
        }
        String command = words.get(0);
        String argument = words.size() > 1 ? words.get(1) : "";

        if (command.equals("SPOOL")) {
            return new Directive(Kind.REJECTED, "SPOOL writes to a file on the client running SQL*Plus and has"
                    + " no meaning here. Results are returned to you as JSON instead.");
        }
        if (!IGNORABLE_COMMANDS.contains(command)) {
            return null;
        }
        if (command.equals("SET")) {
            if (!SQLPLUS_SET_OPTIONS.contains(argument)) {
                // Could be PostgreSQL's SET search_path, MySQL's SET autocommit, ...
                return null;
            }
            if (argument.equals("SERVEROUTPUT")) {
                return new Directive(Kind.IGNORED, "Not needed: DBMS_OUTPUT is enabled automatically and its"
                        + " lines are returned with each statement as \"dbms_output\".");
            }
            return new Directive(Kind.IGNORED, "A SQL*Plus display setting; it does not affect the JSON"
                    + " results returned here.");
        }
        if (command.equals("SHOW")) {
            if (!argument.equals("ERRORS") && !argument.equals("ERR")) {
                // MySQL's SHOW TABLES and friends are real statements.
                return null;
            }
            return new Directive(Kind.IGNORED, "SHOW ERRORS is a SQL*Plus command. To see why a PL/SQL object"
                    + " failed to compile, select from USER_ERRORS instead.");
        }
        return new Directive(Kind.IGNORED, "A SQL*Plus formatting command with no effect on the JSON results"
                + " returned here.");
    }

    private static int endOfLine(String script, int from) {
        int newline = script.indexOf('\n', from);
        return newline < 0 ? script.length() : newline;
    }

    private static boolean isBlankTo(String script, int from, int to) {
        for (int i = from; i < to; i++) {
            if (!Character.isWhitespace(script.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static char previousNonWhitespace(CharSequence buffer) {
        for (int i = buffer.length() - 1; i >= 0; i--) {
            if (!Character.isWhitespace(buffer.charAt(i))) {
                return buffer.charAt(i);
            }
        }
        return ' ';
    }

    private static boolean isWordCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }

    private record Directive(Kind kind, String note) {
    }
}
