package io.quarkiverse.mcp.servers.jdbc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkiverse.mcp.server.runtime.ConnectionManager;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Keeps one JDBC connection per MCP client connection, so that server-side session state survives
 * across tool calls.
 *
 * <p>
 * Without this, every tool call opened and closed its own connection, which meant anything
 * session-scoped - an Oracle VPD context set through {@code DBMS_SESSION.SET_CONTEXT}, an
 * {@code ALTER SESSION}, a session-scoped temporary table - was gone before the next call could
 * use it. See {@code docs/context_issue.md}.
 *
 * <h2>Keying</h2>
 *
 * Sessions are keyed by MCP connection id, which is stable for the STDIO transport and for SSE
 * (one id per {@code /mcp/sse} stream). For the streamable-HTTP transport it is stable only while
 * the client echoes back the {@code Mcp-Session-Id} header it was given; a client that does not
 * gets a fresh MCP connection - and therefore a fresh database session - on every call, exactly as
 * before this class existed. That is why the number of retained connections is capped.
 *
 * <h2>Bounds</h2>
 *
 * A retained connection is real server-side state (an Oracle session, a cursor budget, possibly
 * locks), so it is released when it goes idle, when the MCP client disconnects, or when the cap is
 * reached and it is the least recently used. Set {@code jdbc.session.affinity=false} to restore the
 * old connection-per-call behaviour entirely.
 *
 * <h2>Concurrency</h2>
 *
 * A JDBC {@link Connection} is not thread-safe, and an MCP client may have several tool calls in
 * flight. Each session therefore carries a lock which is held for the whole duration of a tool
 * call: concurrent calls on one MCP connection are serialized rather than racing on one connection.
 */
@Singleton
public class JdbcSessionManager {

    /** How long a connection may sit unused before the sweeper closes it. */
    @ConfigProperty(name = "jdbc.session.idle-timeout", defaultValue = "PT10M")
    Duration idleTimeout;

    /** Upper bound on retained connections, protecting the database from a client that never reuses its session id. */
    @ConfigProperty(name = "jdbc.session.max", defaultValue = "16")
    int maxSessions;

    /** Kill switch: when false every tool call gets its own short-lived connection, as before. */
    @ConfigProperty(name = "jdbc.session.affinity", defaultValue = "true")
    boolean affinityEnabled;

    /**
     * How long a tool call waits for the session lock before it gives up. Without a bound here a
     * single stuck call wedges every later call on the same MCP connection for as long as it runs.
     */
    @ConfigProperty(name = "jdbc.session.lock-timeout", defaultValue = "PT30S")
    Duration lockTimeout;

    /**
     * Whether a call that cannot get the lock may cancel the statement currently holding it. This
     * is what lets the server recover on its own from an abandoned call - see
     * {@link #lockOrFail(Session, String)}.
     */
    @ConfigProperty(name = "jdbc.session.cancel-on-contention", defaultValue = "true")
    boolean cancelOnContention;

    /**
     * Used only to notice that an MCP client has gone away so its database session can be released
     * without waiting out the idle timeout. This is extension-internal API - see the note in
     * CLAUDE.md about checking it when upgrading {@code io.quarkiverse.mcp}.
     */
    @Inject
    ConnectionManager mcpConnections;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private ScheduledExecutorService sweeper;

    /** Seconds allowed for the liveness check performed before a retained connection is handed out. */
    private static final int VALIDATION_TIMEOUT_SECONDS = 5;

    /** Seconds shutdown waits for a running tool call to let go of its session before closing it anyway. */
    private static final int SHUTDOWN_LOCK_TIMEOUT_SECONDS = 5;

    @PostConstruct
    void startSweeper() {
        if (!affinityEnabled) {
            return;
        }
        // Run at half the idle timeout so a connection is closed reasonably promptly after
        // expiring, but never more often than once a second.
        long periodMillis = Math.max(1_000L, idleTimeout.toMillis() / 2);
        sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jdbc-mcp-session-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        sweeper.scheduleWithFixedDelay(this::sweep, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
        for (String key : List.copyOf(sessions.keySet())) {
            Session session = sessions.remove(key);
            if (session == null) {
                continue;
            }
            // Never block shutdown on a call that is still running: cut its statement short, give
            // it a moment to unwind, then close the connection whether it let go or not.
            session.cancelActiveStatement();
            boolean locked = false;
            try {
                locked = session.lock.tryLock(SHUTDOWN_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                session.evicted = true;
                closeQuietly(session.connection);
                session.connection = null;
            } finally {
                if (locked) {
                    session.lock.unlock();
                }
            }
        }
    }

    /**
     * Borrows the database connection belonging to an MCP connection, opening it if necessary.
     *
     * <p>
     * The returned lease holds the session lock and <b>must</b> be closed, normally through
     * try-with-resources. Closing it releases the lock but keeps the underlying connection open,
     * unless affinity is off or no MCP connection id was available, in which case the connection is
     * closed as it always used to be.
     *
     * @param mcpConnectionId the MCP connection id, or {@code null} when unknown
     */
    public Lease acquire(String mcpConnectionId, String url, String user, String password) throws SQLException {
        if (!affinityEnabled || mcpConnectionId == null) {
            return new Lease(null, open(url, user, password));
        }

        while (true) {
            Session session = sessions.computeIfAbsent(mcpConnectionId, key -> new Session());
            lockOrFail(session, mcpConnectionId);
            if (session.evicted) {
                // The sweeper removed this session between the lookup and the lock; try again and
                // we will create a fresh one.
                session.lock.unlock();
                continue;
            }
            try {
                boolean opened = refresh(session, url, user, password);
                session.lastUsedNanos = System.nanoTime();
                if (opened) {
                    enforceCapacity(mcpConnectionId);
                }
                return new Lease(session, session.connection);
            } catch (SQLException | RuntimeException e) {
                session.lock.unlock();
                throw e;
            }
        }
    }

    /**
     * Takes the session lock, waiting at most {@link #lockTimeout}.
     *
     * <p>
     * The lock is held for a whole tool call, so whoever holds it is running a statement. If that
     * statement outlives the wait, the caller is in one of two situations and neither is worth
     * waiting out: the holder is an <em>abandoned</em> call whose MCP client already timed out and
     * will never read the result, or it is a query so slow that no answer will arrive in time
     * anyway. Blocking indefinitely would queue up every later call on this MCP connection behind
     * it - the server would look completely dead until the query finished or it was restarted.
     * So the holder's statement is cancelled and the lock is waited for once more.
     */
    private void lockOrFail(Session session, String mcpConnectionId) throws SQLException {
        if (awaitLock(session)) {
            return;
        }
        if (cancelOnContention) {
            Log.warnf("A tool call on MCP connection %s has held its JDBC session for longer than %s;"
                    + " cancelling its statement so the session can be reused", mcpConnectionId, lockTimeout);
            session.cancelActiveStatement();
            if (awaitLock(session)) {
                return;
            }
        }
        throw new SQLException("Another tool call is still running on this MCP session and did not release it"
                + " within " + lockTimeout + ". Wait for it to finish, or run a smaller query.");
    }

    private boolean awaitLock(Session session) throws SQLException {
        try {
            return session.lock.tryLock(lockTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for the JDBC session", e);
        }
    }

    /**
     * Makes sure the session holds a usable connection for the given credentials.
     *
     * @return true if a new connection was opened
     */
    private boolean refresh(Session session, String url, String user, String password) throws SQLException {
        if (session.connection != null && (!session.matches(url, user, password) || !isUsable(session.connection))) {
            // Either the caller switched database/identity on the same MCP connection, or the
            // connection died while it was idle. Either way the old one is of no further use, and
            // any session state on it is already lost.
            closeQuietly(session.connection);
            session.connection = null;
        }
        if (session.connection != null) {
            return false;
        }
        session.connection = open(url, user, password);
        session.url = url;
        session.user = user;
        session.password = password;
        return true;
    }

    private static Connection open(String url, String user, String password) throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    private static boolean isUsable(Connection connection) {
        try {
            return !connection.isClosed() && connection.isValid(VALIDATION_TIMEOUT_SECONDS);
        } catch (SQLException e) {
            return false;
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            Log.debugf(e, "Failed to close a pooled JDBC connection");
        }
    }

    /** Closes the least recently used sessions until the cap is respected. */
    private void enforceCapacity(String keepKey) {
        if (sessions.size() <= maxSessions) {
            return;
        }
        List<Map.Entry<String, Session>> candidates = new ArrayList<>(sessions.entrySet());
        candidates.sort(Comparator.comparingLong(entry -> entry.getValue().lastUsedNanos));
        for (Map.Entry<String, Session> entry : candidates) {
            if (sessions.size() <= maxSessions) {
                return;
            }
            if (entry.getKey().equals(keepKey)) {
                continue;
            }
            evictIfFree(entry.getKey(), entry.getValue(), "session limit of " + maxSessions + " reached");
        }
    }

    private void sweep() {
        try {
            long idleNanos = idleTimeout.toNanos();
            for (Map.Entry<String, Session> entry : sessions.entrySet()) {
                Session session = entry.getValue();
                boolean idle = System.nanoTime() - session.lastUsedNanos > idleNanos;
                boolean clientGone = mcpConnections.get(entry.getKey()) == null;
                if (idle || clientGone) {
                    evictIfFree(entry.getKey(), session, idle ? "idle for longer than " + idleTimeout
                            : "MCP client disconnected");
                }
            }
        } catch (Exception e) {
            // A sweep failure must never kill the scheduler - the next pass will retry.
            Log.warnf(e, "Failed to sweep idle JDBC sessions");
        }
    }

    /** Evicts a session, but only if no tool call currently holds it. */
    private void evictIfFree(String key, Session session, String reason) {
        if (!session.lock.tryLock()) {
            return;
        }
        try {
            if (session.evicted) {
                return;
            }
            session.evicted = true;
            sessions.remove(key, session);
            closeQuietly(session.connection);
            session.connection = null;
            Log.debugf("Closed the JDBC session for MCP connection %s (%s)", key, reason);
        } finally {
            session.lock.unlock();
        }
    }

    /** Number of database connections currently retained. Exposed for diagnostics and tests. */
    public int retainedSessions() {
        return sessions.size();
    }

    public boolean isAffinityEnabled() {
        return affinityEnabled;
    }

    private static final class Session {
        private final ReentrantLock lock = new ReentrantLock();
        private Connection connection;
        private String url;
        private String user;
        private String password;
        private long lastUsedNanos = System.nanoTime();
        private boolean evicted;

        /**
         * The statement of the tool call currently holding the lock, so that a later call can cut
         * it short. Written by the lock holder and read by other threads, hence volatile.
         */
        private volatile Statement activeStatement;

        boolean matches(String url, String user, String password) {
            return Objects.equals(this.url, url)
                    && Objects.equals(this.user, user)
                    && Objects.equals(this.password, password);
        }

        /**
         * Best effort: {@link Statement#cancel()} is safe to call from another thread, but a driver
         * may refuse it, and the statement may finish between the read and the call.
         */
        void cancelActiveStatement() {
            Statement statement = activeStatement;
            if (statement == null) {
                return;
            }
            try {
                statement.cancel();
            } catch (SQLException | RuntimeException e) {
                Log.debugf(e, "Failed to cancel the statement holding a JDBC session");
            }
        }
    }

    /**
     * A borrowed connection. Closing it releases the session for the next tool call; it does not
     * close a retained connection.
     */
    public final class Lease implements AutoCloseable {

        private final Session session;
        private final Connection connection;
        private boolean released;

        private Lease(Session session, Connection connection) {
            this.session = session;
            this.connection = connection;
        }

        public Connection connection() {
            return connection;
        }

        /**
         * Publishes the statement this tool call is about to run, so that a later call blocked on
         * the session can cancel it instead of waiting forever. Called for every statement; the
         * last one wins, which is what matters because only the running one can be stuck.
         */
        public void track(Statement statement) {
            if (session != null) {
                session.activeStatement = statement;
            }
        }

        /** True when the connection outlives this tool call and keeps its session state. */
        public boolean retained() {
            return session != null;
        }

        @Override
        public void close() {
            if (released) {
                return;
            }
            released = true;
            if (session == null) {
                closeQuietly(connection);
                return;
            }
            session.activeStatement = null;
            session.lastUsedNanos = System.nanoTime();
            session.lock.unlock();
        }
    }
}
