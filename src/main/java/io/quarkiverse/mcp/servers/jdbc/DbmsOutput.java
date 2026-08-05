package io.quarkiverse.mcp.servers.jdbc;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.quarkus.logging.Log;

/**
 * Captures Oracle's {@code DBMS_OUTPUT} buffer.
 *
 * <p>
 * {@code DBMS_OUTPUT.PUT_LINE} is how a PL/SQL block reports anything back to a human, but the lines
 * only sit in a server-side buffer until a client asks for them - which is what SQL*Plus's
 * {@code SET SERVEROUTPUT ON} arranges. Without an equivalent here, a model writing a block with
 * {@code PUT_LINE} calls in it would see nothing at all and have no way to debug.
 *
 * <p>
 * Everything is best-effort: on a non-Oracle database, or if the package is unavailable, capture is
 * simply off and the script still runs.
 */
final class DbmsOutput {

    private DbmsOutput() {
    }

    /**
     * Turns on output buffering for this connection.
     *
     * @return whether output can now be captured
     */
    static boolean enable(Connection connection) {
        try {
            if (!isOracle(connection)) {
                return false;
            }
            // NULL means an unlimited buffer; the caller bounds what it reads back instead.
            try (CallableStatement call = connection.prepareCall("BEGIN DBMS_OUTPUT.ENABLE(NULL); END;")) {
                call.execute();
            }
            return true;
        } catch (SQLException e) {
            Log.debugf(e, "DBMS_OUTPUT capture is unavailable on this connection");
            return false;
        }
    }

    /**
     * Reads and clears whatever has been buffered since the last call.
     *
     * <p>
     * Lines beyond {@code maxLines} stay in the buffer and turn up on the next drain rather than
     * being lost, so a chatty loop cannot flood a single tool result.
     */
    static List<String> drain(Connection connection, int maxLines) {
        List<String> lines = new ArrayList<>();
        // GET_LINE is one round trip per line, but it needs no vendor-specific array binding, which
        // GET_LINES would. Scripts that print thousands of lines are not the case worth optimising.
        try (CallableStatement call = connection.prepareCall("BEGIN DBMS_OUTPUT.GET_LINE(?, ?); END;")) {
            call.registerOutParameter(1, Types.VARCHAR);
            call.registerOutParameter(2, Types.INTEGER);
            while (lines.size() < maxLines) {
                call.execute();
                if (call.getInt(2) != 0) {
                    // Status 1: the buffer is empty.
                    break;
                }
                String line = call.getString(1);
                lines.add(line == null ? "" : line);
            }
        } catch (SQLException e) {
            Log.debugf(e, "Failed to read the DBMS_OUTPUT buffer");
        }
        return lines;
    }

    private static boolean isOracle(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName();
        return product != null && product.toLowerCase(Locale.ROOT).contains("oracle");
    }
}
