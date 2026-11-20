package dev.phpruntime.builtins;

import com.oracle.truffle.api.object.DynamicObject;
import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.GuestScheduler;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpClassTemplate;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpExceptions;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpRequest;
import dev.phpruntime.runtime.PhpStrings;
import dev.phpruntime.runtime.Visibility;
import dev.phpruntime.snapshot.SnapshotGeneration;
import dev.phpruntime.snapshot.SnapshotRun;

import java.lang.reflect.Field;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * The procedural mysqli API over JDBC (MariaDB Connector/J), enough for WordPress's wpdb.
 *
 * <p>Connections and results are objects of the built-in classes {@code mysqli} and
 * {@code mysqli_result}; their Java state lives in a per-request table keyed by object id.
 * Every call that talks to the server runs inside {@code scheduler.blocking}, so a slow query
 * gives its CPU slot to other requests. Results are read fully when the query runs, so
 * fetching never blocks. Values come back as strings, as mysqli does by default.
 *
 * <p>Closing a link does not normally close the JDBC connection: it is reset with
 * MariaDB's {@code COM_RESET_CONNECTION} and returned to a small engine-wide pool, so the next
 * request with the same URL, credentials and driver properties can skip the connect handshake.
 * A reset session keeps the database it last had, so a link tracks both the database PHP asked
 * for (the logical catalog) and the one its JDBC connection actually has (the physical catalog).
 * PHP only sees the difference when it talks to the server: before a statement that can use the
 * database runs, a link whose catalogs differ gets a fresh connection, and the pooled one is
 * discarded. Session setup that cannot name a table or ask for the current database is the one
 * exception, so a request that selects its database after setting the charset and sql_mode can
 * still reuse the pooled connection.
 */
final class MysqliFunctions {

    private MysqliFunctions() {
    }

    static final long ASSOC = 1;
    static final long NUM = 2;
    static final long BOTH = 3;
    static final long REPORT_ERROR = 1;
    static final long REPORT_STRICT = 2;

    private static final String CLIENT_INFO = "mysqlnd 8.5.0";
    private static final long CLIENT_VERSION = 80500;
    private static final Pattern CONN_PREFIX = Pattern.compile("^\\(conn=\\d+\\) ");

    /** The session values a fresh connection has, and that a reset has to restore. */
    private static final String SESSION_QUERY = "SELECT @@SESSION.sql_mode, "
            + "@@character_set_client, @@character_set_connection, @@character_set_results, "
            + "@@collation_connection, @@autocommit, @@time_zone, @@transaction_isolation, "
            + "@@SESSION.session_track_system_variables";
    private static final int MAX_IDLE_PER_KEY = 16;
    private static final long MAX_IDLE_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final long VALIDATE_AFTER_NANOS = TimeUnit.SECONDS.toNanos(3);

    /** Counts JDBC connections opened by this process, so a test can prove lazy restore. */
    private static final AtomicLong OPENED_CONNECTIONS = new AtomicLong();

    // ------------------------------------------------------------------ state

    /** Per-request mysqli state. */
    static final class State {
        final Map<Long, Link> links = new HashMap<>();
        final Map<Long, Result> results = new HashMap<>();
        final Map<Long, Prepared> statements = new HashMap<>();
        long reportMode = REPORT_ERROR | REPORT_STRICT;
        int connectErrno;
        String connectError;
    }

    /** A connection handle (the Java side of a {@code mysqli} object). */
    static final class Link implements AutoCloseable {
        Connection conn;
        String charset = "utf8mb4";
        String host = "";
        String serverInfo = "";
        int errno;
        String error = "";
        String sqlstate = "00000";
        long affectedRows;
        long insertId;
        int fieldCount;
        boolean closed;

        /** Non-null when this link may go back to the pool. */
        PoolKey key;
        SessionState freshSession;
        String poolServerInfo;
        boolean poolable;

        /** Everything needed to open another connection exactly like the original connect. */
        String url;
        Properties driverProps;

        /** The database PHP asked for, or null when it asked for none. */
        String logicalCatalog;
        /** The database the current JDBC session actually has, or null when it has none. */
        String physicalCatalog;

        /** The PHP object this link belongs to, for refreshing its properties after a statement. */
        PhpObject object;

        /** Set for a link restored from a snapshot: conn is null until the first server operation. */
        boolean restored;
        /** Session setup recorded while a template run captures, or replayed by a restored link. */
        List<MysqliSnapshot.Setup> setup;
        /** Whether a transaction is currently open (tracked while a template run records). */
        boolean transaction;
        /** Autocommit as PHP last set it (tracked while a template run records). */
        boolean autocommit = true;
        /** Whether a statement ran after autocommit was switched off. */
        boolean ranSinceAutocommitOff;
        /** Refusals found while recording; empty outside a template run. */
        final Set<String> refusals = new LinkedHashSet<>();

        /** mysqli_options values set before connecting. */
        final Map<Long, Object> options = new LinkedHashMap<>();
        String initCommand;

        @Override
        public void close() throws SQLException {
            closed = true;
            Connection c = conn;
            conn = null;
            if (c == null) {
                return;
            }
            if (poolable) {
                try {
                    Pool.release(PhpRequest.current().scheduler, key, c, poolServerInfo, freshSession);
                    return;
                } catch (SQLException | RuntimeException ignored) {
                    // the connection is in an unknown state; discard it below
                }
            }
            c.close();
        }
    }

    /** One prepared statement (the Java side of a {@code mysqli_stmt} object). */
    static final class Prepared {
        long linkId;
        String sql = "";
        boolean bump;
        PreparedStatement ps;
        boolean closed;
        Result result;
        long affectedRows;
        long insertId;
        long fieldCount;
        int errno;
        String error = "";
        String sqlstate = "00000";
    }

    /** The session values a reset connection has to restore to the values a fresh one had. */
    record SessionState(String sqlMode, String charsetClient, String charsetConnection,
                        String charsetResults, String collationConnection, String autocommit,
                        String timeZone, String transactionIsolation, String sessionTrack) {
    }

    /** Everything that determines the session at connect time, and therefore what may be reused. */
    record PoolKey(String url, String user, String password, Map<String, String> properties) {
    }

    /** An idle connection and the state it had when it was opened. */
    record Idle(Connection connection, String serverInfo, SessionState session, String catalog,
                long sinceNanos) {
    }

    /** A column as mysqli_fetch_field describes it. */
    record FieldInfo(String name, String orgname, String table, String orgtable, String db,
                     long length, long charsetnr, long flags, long type, long decimals) {
    }

    /** A fully read result set (the Java side of a {@code mysqli_result} object). */
    static final class Result {
        final List<FieldInfo> fields;
        final List<Object[]> rows;
        int position;
        int fieldPosition;
        boolean freed;

        Result(List<FieldInfo> fields, List<Object[]> rows) {
            this.fields = fields;
            this.rows = rows;
        }
    }

    private static State state(PhpContext ctx) {
        ContextState cs = ContextState.of(ctx);
        if (cs.mysqli == null) {
            cs.mysqli = new State();
        }
        return cs.mysqli;
    }

    // ------------------------------------------------------------------ snapshot state

    private static final String USER_VARIABLE_LEFT = "a user variable was set by boot";
    private static final String TRANSACTION_LEFT = "a transaction was left open by boot";
    private static final String TEMPORARY_TABLE_LEFT = "a temporary table was left by boot";
    private static final String TABLE_LOCK_LEFT = "a table lock was left by boot";
    private static final String PREPARED_LEFT = "a prepared statement was left open by boot";
    private static final String RESULT_LEFT = "an unfreed mysqli result was left by boot";
    private static final String SESSION_STATEMENT_LEFT = "a session statement was left by boot";

    private static boolean recording(PhpContext ctx) {
        SnapshotRun run = ctx.snapshotRun();
        return run != null && run.isTemplate();
    }

    /** Reasons the mysqli state at the end of boot cannot be carried into a snapshot. */
    static List<String> snapshotRefusals(PhpContext ctx) {
        State st = ContextState.of(ctx).mysqli;
        if (st == null) {
            return List.of();
        }
        Set<String> reasons = new LinkedHashSet<>();
        for (Link link : st.links.values()) {
            if (link.closed) {
                continue;
            }
            reasons.addAll(link.refusals);
            if (link.transaction || (!link.autocommit && link.ranSinceAutocommitOff)) {
                reasons.add(TRANSACTION_LEFT);
            }
        }
        if (!st.statements.isEmpty()) {
            reasons.add(PREPARED_LEFT);
        }
        for (Result result : st.results.values()) {
            // A result whose rows were all read is only a handle: WordPress keeps $wpdb->result
            // that way. Rows still waiting to be read are consumption state and are refused.
            if (!result.freed && result.position < result.rows.size()) {
                reasons.add(RESULT_LEFT);
            }
        }
        return new ArrayList<>(reasons);
    }

    /** The copyable mysqli state, or null when boot did not touch mysqli. */
    static MysqliSnapshot snapshot(PhpContext ctx) {
        State st = ContextState.of(ctx).mysqli;
        if (st == null) {
            return null;
        }
        List<MysqliSnapshot.LinkState> links = new ArrayList<>();
        for (Map.Entry<Long, Link> entry : st.links.entrySet()) {
            Link link = entry.getValue();
            Map<String, String> props = new LinkedHashMap<>();
            if (link.driverProps != null) {
                for (String name : link.driverProps.stringPropertyNames()) {
                    props.put(name, link.driverProps.getProperty(name));
                }
            }
            links.add(new MysqliSnapshot.LinkState(entry.getKey(), link.url, Map.copyOf(props), link.host,
                    link.serverInfo, link.charset, link.logicalCatalog, link.physicalCatalog,
                    Map.copyOf(link.options), link.initCommand, link.closed, link.autocommit,
                    link.affectedRows, link.insertId, link.fieldCount, link.errno, link.error,
                    link.sqlstate, link.setup == null ? List.of() : List.copyOf(link.setup)));
        }
        List<MysqliSnapshot.ResultState> results = new ArrayList<>();
        for (Map.Entry<Long, Result> entry : st.results.entrySet()) {
            Result result = entry.getValue();
            results.add(new MysqliSnapshot.ResultState(entry.getKey(), List.copyOf(result.fields),
                    result.rows, result.position, result.fieldPosition, result.freed));
        }
        return new MysqliSnapshot(st.reportMode, st.connectErrno, st.connectError, links, results);
    }

    /** Rebuilds a request's mysqli state from a snapshot; links stay unconnected until used. */
    static State restore(PhpContext ctx, MysqliSnapshot snap) {
        if (snap == null) {
            return null;
        }
        State st = new State();
        st.reportMode = snap.reportMode;
        st.connectErrno = snap.connectErrno;
        st.connectError = snap.connectError;
        for (MysqliSnapshot.LinkState in : snap.links) {
            Link link = new Link();
            link.restored = true;
            link.closed = in.closed();
            link.host = in.host();
            link.serverInfo = in.serverInfo();
            link.charset = in.charset();
            link.logicalCatalog = in.logicalCatalog();
            link.physicalCatalog = in.physicalCatalog();
            link.affectedRows = in.affectedRows();
            link.insertId = in.insertId();
            link.fieldCount = (int) in.fieldCount();
            link.errno = in.errno();
            link.error = in.error();
            link.sqlstate = in.sqlstate();
            link.autocommit = in.autocommit();
            link.url = in.url();
            if (in.properties() != null) {
                Properties props = new Properties();
                props.putAll(in.properties());
                link.driverProps = props;
            }
            if (in.options() != null) {
                link.options.putAll(in.options());
            }
            link.initCommand = in.initCommand();
            link.setup = new ArrayList<>(in.setup());
            st.links.put(in.objectId(), link);
        }
        for (MysqliSnapshot.ResultState in : snap.results) {
            Result result = new Result(in.fields(), in.rows());
            result.position = in.position();
            result.fieldPosition = in.fieldPosition();
            result.freed = in.freed();
            st.results.put(in.objectId(), result);
        }
        return st;
    }

    static long connectionsOpenedForTest() {
        return OPENED_CONNECTIONS.get();
    }

    /**
     * Opens a restored link and replays the session setup boot performed, inside
     * {@code blocking(...)}. Returns false after reporting a lost connection on {@code fn}.
     */
    private static boolean ensureConnected(PhpContext ctx, PhpObject obj, Link link, String fn) {
        if (link.conn != null) {
            return true;
        }
        if (!link.restored) {
            throw new PhpError("mysqli object is not fully initialized");
        }
        if (link.url == null) {
            // An init-only link restored from a snapshot has no connect parameters.
            throw new PhpError("mysqli object is not fully initialized");
        }
        try {
            Opened opened = blocking(ctx, () -> open(null, link.url, link.driverProps, false, false));
            link.conn = opened.connection();
            link.closed = false;
            link.serverInfo = PhpStrings.fromJava(opened.serverInfo());
            link.poolServerInfo = opened.serverInfo();
            link.freshSession = opened.session();
            link.poolable = false;
            link.physicalCatalog = opened.catalog();
            if (link.initCommand != null && !link.initCommand.isEmpty()) {
                runSetup(ctx, link, link.initCommand);
            }
            replay(ctx, link);
            // The captured view is authoritative; replay should already have produced it.
            if (link.logicalCatalog != null && !link.logicalCatalog.equals(link.physicalCatalog)) {
                blocking(ctx, () -> {
                    link.conn.setCatalog(link.logicalCatalog);
                    return null;
                });
                link.physicalCatalog = link.logicalCatalog;
            }
            ctx.request().resources.add(link);
            sync(ctx, obj, link);
            return true;
        } catch (SQLException e) {
            // A restored link in php would already have failed at boot; the operation that
            // needed the server reports the loss as mysqlnd does after the server goes away.
            link.errno = 2006;
            link.error = "MySQL server has gone away";
            link.sqlstate = "HY000";
            link.affectedRows = -1;
            report(ctx, fn, link.sqlstate, 2006, link.error);
            syncQuery(obj, link);
            return false;
        }
    }

    /** Runs the recorded setup in order on the freshly opened connection. */
    private static void replay(PhpContext ctx, Link link) throws SQLException {
        for (MysqliSnapshot.Setup step : link.setup) {
            switch (step.kind()) {
                case SQL -> runSetup(ctx, link, step.text());
                case CHARSET -> {
                    runSetup(ctx, link, "SET NAMES " + step.text());
                    link.charset = step.text();
                }
                case DATABASE -> {
                    blocking(ctx, () -> {
                        link.conn.setCatalog(step.text());
                        return null;
                    });
                    link.physicalCatalog = step.text();
                    link.logicalCatalog = step.text();
                }
                case AUTOCOMMIT -> {
                    blocking(ctx, () -> {
                        link.conn.setAutoCommit(step.flag());
                        return null;
                    });
                    link.autocommit = step.flag();
                }
                case OPTION -> {
                    // Connect parameters already carry the option's effect.
                }
            }
        }
    }

    private static void runSetup(PhpContext ctx, Link link, String sql) throws SQLException {
        String javaSql = MysqliSql.toJava(sql);
        blocking(ctx, () -> {
            try (Statement st = link.conn.createStatement()) {
                st.execute(javaSql);
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ classification

    /** The first SQL keyword, skipping whitespace, comments and leading parentheses. */
    static String leadingKeyword(String sql) {
        int i = skipTrivia(sql, 0);
        int n = sql.length();
        while (i < n && sql.charAt(i) == '(') {
            i = skipTrivia(sql, i + 1);
        }
        int start = i;
        while (i < n && Character.isLetter(sql.charAt(i))) {
            i++;
        }
        return sql.substring(start, i).toUpperCase(Locale.ROOT);
    }

    /** The keyword after the first one, or "" when there is none. */
    private static String secondKeyword(String sql) {
        int i = skipTrivia(sql, 0);
        int n = sql.length();
        while (i < n && Character.isLetter(sql.charAt(i))) {
            i++;
        }
        i = skipTrivia(sql, i);
        int start = i;
        while (i < n && Character.isLetter(sql.charAt(i))) {
            i++;
        }
        return sql.substring(start, i).toUpperCase(Locale.ROOT);
    }

    private static int skipTrivia(String sql, int i) {
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '#') {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? n : end + 1;
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-'
                    && (i + 2 >= n || Character.isWhitespace(sql.charAt(i + 2)))) {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? n : end + 1;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            break;
        }
        return i;
    }

    /**
     * Statements that cannot change data. Everything else bumps the snapshot generation before
     * it is sent, including DDL and statements whose leading keyword is not recognised.
     */
    static boolean writesStatement(String sql) {
        String keyword = leadingKeyword(sql);
        return switch (keyword) {
            case "SELECT", "SHOW", "SET", "DESCRIBE", "DESC", "EXPLAIN", "USE", "DO",
                    "BEGIN", "COMMIT", "ROLLBACK" -> false;
            case "START" -> !"TRANSACTION".equals(secondKeyword(sql));
            default -> true;
        };
    }

    private static final Pattern SET_AUTOCOMMIT = Pattern.compile(
            "(?is)^SET\\s+(?:SESSION\\s+)?autocommit\\s*=\\s*(0|1|on|off)$");
    private static final Pattern SET_USER_VARIABLE = Pattern.compile("(?is)^SET\\s+@(?!@).*");
    private static final Pattern SET_GLOBAL = Pattern.compile("(?is)^SET\\s+(?:@@)?global[\\s.].*");

    /** Tracks one statement in a template run and records it when it is safe session setup. */
    private static void recordStatement(PhpContext ctx, Link link, String sql) {
        if (!recording(ctx)) {
            return;
        }
        String keyword = leadingKeyword(sql);
        switch (keyword) {
            case "SELECT", "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "DO" -> {
                markTransaction(link, keyword);
            }
            case "SET" -> {
                String stripped = sql.stripLeading();
                java.util.regex.Matcher auto = SET_AUTOCOMMIT.matcher(stripped);
                if (SET_GLOBAL.matcher(stripped).matches()) {
                    // Server-global state, not this session's.
                } else if (auto.matches()) {
                    boolean on = auto.group(1).equals("1") || auto.group(1).equalsIgnoreCase("on");
                    link.autocommit = on;
                    link.ranSinceAutocommitOff = false;
                    if (on) {
                        link.transaction = false;
                    }
                    addSetup(link, MysqliSnapshot.Setup.autocommit(on));
                } else if (databaseIndependent(sql)) {
                    addSetup(link, MysqliSnapshot.Setup.sql(sql));
                } else if (SET_USER_VARIABLE.matcher(stripped).matches()) {
                    link.refusals.add(USER_VARIABLE_LEFT);
                } else {
                    link.refusals.add(SESSION_STATEMENT_LEFT);
                }
            }
            case "BEGIN", "START" -> link.transaction = true;
            case "COMMIT", "ROLLBACK" -> link.transaction = false;
            case "CREATE", "DROP", "ALTER", "TRUNCATE", "RENAME" -> {
                // DDL commits an open transaction implicitly.
                link.transaction = false;
                if (upper(sql).contains("TEMPORARY")) {
                    link.refusals.add(TEMPORARY_TABLE_LEFT);
                }
            }
            case "USE" -> {
                addSetup(link, MysqliSnapshot.Setup.sql(sql));
            }
            case "LOCK" -> link.refusals.add(TABLE_LOCK_LEFT);
            case "UNLOCK" -> link.refusals.remove(TABLE_LOCK_LEFT);
            case "PREPARE" -> link.refusals.add(PREPARED_LEFT);
            default -> markTransaction(link, keyword);
        }
    }

    private static void markTransaction(Link link, String keyword) {
        if (link.autocommit) {
            return;
        }
        if (!keyword.equals("SET") && !keyword.equals("SHOW") && !keyword.equals("DO")) {
            link.ranSinceAutocommitOff = true;
        }
    }

    private static void addSetup(Link link, MysqliSnapshot.Setup step) {
        if (link.setup == null) {
            link.setup = new ArrayList<>();
        }
        link.setup.add(step);
    }

    private static String upper(String sql) {
        return sql.toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ pool

    /**
     * Idle JDBC connections, shared by every language context in the engine. A connection is
     * only offered to the pool after its session was reset and made equal to a fresh one.
     */
    static final class Pool {
        private static final Map<PoolKey, Deque<Idle>> IDLE = new ConcurrentHashMap<>();

        private Pool() {
        }

        /**
         * Off unless -Dblitz.mysqli.pool=true. Measured on the WordPress front page (2026-11-19): no
         * latency gain at steady state, and each idle pooled connection kept about 32 MB of driver
         * buffers alive (16 connections, about 510 MB of live heap).
         */
        static boolean enabled() {
            return "true".equalsIgnoreCase(System.getProperty("blitz.mysqli.pool"));
        }

        static Idle take(PoolKey key) {
            Deque<Idle> idle = IDLE.get(key);
            if (idle == null) {
                return null;
            }
            synchronized (idle) {
                long now = System.nanoTime();
                while (!idle.isEmpty()) {
                    Idle head = idle.pollFirst();
                    long age = now - head.sinceNanos();
                    if (age > MAX_IDLE_NANOS) {
                        closeQuietly(head.connection());
                        continue;
                    }
                    if (age > VALIDATE_AFTER_NANOS) {
                        try {
                            if (!head.connection().isValid(1)) {
                                closeQuietly(head.connection());
                                continue;
                            }
                        } catch (SQLException | RuntimeException e) {
                            closeQuietly(head.connection());
                            continue;
                        }
                    }
                    return head;
                }
            }
            return null;
        }

        /** Returns false when the pool is full and the caller has to close the connection. */
        static boolean offer(PoolKey key, Connection c, String serverInfo, SessionState session, String catalog) {
            Deque<Idle> idle = IDLE.computeIfAbsent(key, k -> new ArrayDeque<>());
            synchronized (idle) {
                long now = System.nanoTime();
                while (!idle.isEmpty() && now - idle.peekLast().sinceNanos() > MAX_IDLE_NANOS) {
                    closeQuietly(idle.pollLast().connection());
                }
                if (idle.size() >= MAX_IDLE_PER_KEY) {
                    return false;
                }
                idle.addFirst(new Idle(c, serverInfo, session, catalog, now));
                return true;
            }
        }

        static void release(GuestScheduler scheduler, PoolKey key, Connection c, String serverInfo,
                            SessionState fresh) throws SQLException {
            if (key == null || fresh == null) {
                c.close();
                return;
            }
            boolean pooled = blocking(scheduler, () -> {
                ((org.mariadb.jdbc.Connection) c).reset();
                // The reset session keeps the database the connection last had; a request that
                // connected with wp in the URL and never switched still has wp here, while a
                // no-database request that selected wp also has wp. Record whatever it is, so
                // the next link knows which database its JDBC session starts with.
                String catalog = c.getCatalog();
                SessionState current = readSession(c);
                if (!current.equals(fresh)) {
                    restoreSession(c, fresh, current);
                }
                return offer(key, c, serverInfo, fresh, catalog);
            });
            if (!pooled) {
                c.close();
            }
        }

        private static void closeQuietly(Connection c) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // discarded either way
            }
        }
    }

    /** Reads the session values the pool compares before and after a reset. */
    private static SessionState readSession(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(SESSION_QUERY)) {
            rs.next();
            return new SessionState(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9));
        }
    }

    /** Re-applies what the driver set up at connect time and COM_RESET_CONNECTION undid. */
    private static void restoreSession(Connection c, SessionState fresh, SessionState current) throws SQLException {
        StringBuilder sql = new StringBuilder("SET SESSION ");
        addAssignment(sql, "sql_mode", fresh.sqlMode(), current.sqlMode());
        addAssignment(sql, "session_track_system_variables", fresh.sessionTrack(), current.sessionTrack());
        addAssignment(sql, "character_set_client", fresh.charsetClient(), current.charsetClient());
        addAssignment(sql, "character_set_connection", fresh.charsetConnection(), current.charsetConnection());
        addAssignment(sql, "character_set_results", fresh.charsetResults(), current.charsetResults());
        addAssignment(sql, "collation_connection", fresh.collationConnection(), current.collationConnection());
        addAssignment(sql, "autocommit", fresh.autocommit(), current.autocommit());
        addAssignment(sql, "time_zone", fresh.timeZone(), current.timeZone());
        addAssignment(sql, "transaction_isolation", fresh.transactionIsolation(), current.transactionIsolation());
        if (sql.length() == "SET SESSION ".length()) {
            return;
        }
        try (Statement st = c.createStatement()) {
            st.execute(sql.toString());
        }
    }

    private static void addAssignment(StringBuilder sql, String name, String fresh, String current) {
        if (Objects.equals(fresh, current)) {
            return;
        }
        if (sql.length() > "SET SESSION ".length()) {
            sql.append(", ");
        }
        sql.append(name).append('=').append(literal(fresh));
    }

    private static String literal(String value) {
        return value == null ? "NULL" : "'" + value.replace("'", "''") + "'";
    }

    /** Whether the server implements COM_RESET_CONNECTION, which is what makes pooling safe. */
    private static boolean supportsReset(String serverInfo) {
        if (serverInfo == null || !serverInfo.contains("MariaDB")) {
            return false;
        }
        // Some MariaDB 10.x servers announce "5.5.5-10.x.y-MariaDB" for old clients.
        String version = serverInfo.startsWith("5.5.5-") ? serverInfo.substring(6) : serverInfo;
        String[] parts = version.split("[.-]");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = Integer.parseInt(parts[2]);
            if (major != 10) {
                return major > 10;
            }
            if (minor != 2 && minor != 3) {
                return minor > 3;
            }
            return minor == 3 ? patch >= 13 : patch >= 22;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ classes

    private static PhpClassTemplate.PropertyDecl prop(String name, Object def) {
        return new PhpClassTemplate.PropertyDecl(name, Visibility.PUBLIC, false, def, null);
    }

    private static final String[] LINK_PROPS = {
            "affected_rows", "client_info", "client_version", "connect_errno", "connect_error", "errno", "error",
            "error_list", "field_count", "host_info", "info", "insert_id", "server_info", "server_version",
            "sqlstate", "protocol_version", "thread_id", "warning_count"};

    private static final List<PhpClassTemplate.PropertyDecl> RESULT_PROPS = List.of(
            prop("current_field", 0L), prop("field_count", 0L), prop("lengths", PhpNull.INSTANCE),
            prop("num_rows", 0L), prop("type", 0L));

    private static final List<PhpClassTemplate.PropertyDecl> STMT_PROPS = List.of(
            prop("affected_rows", 0L), prop("errno", 0L), prop("error", ""),
            prop("error_list", PhpNull.INSTANCE), prop("field_count", 0L), prop("insert_id", 0L),
            prop("num_rows", 0L), prop("param_count", 0L), prop("sqlstate", "00000"));

    private static PhpClassTemplate mysqliTemplate(PhpContext ctx) {
        PhpClassTemplate tpl = ctx.language().mysqliTemplateOrNull();
        return tpl != null ? tpl : ctx.language().mysqliTemplate(linkProps(), linkMethods(ctx));
    }

    /** The mysqli object's methods; they share the builtins' close and select_db paths. */
    private static Map<String, PhpClassTemplate.MethodDecl> linkMethods(PhpContext ctx) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new HashMap<>();
        methods.put("close", method(ctx, "close", (c, self, args) -> {
            Link l = link(c, self, "mysqli::close");
            try {
                l.close();
            } catch (SQLException ignored) {
                // closing a broken connection still closes it
            }
            c.request().resources.remove(l);
            return true;
        }));
        methods.put("select_db", method(ctx, "select_db", (c, self, args) -> {
            if (args.length < 1) {
                throw new PhpError(PhpError.ARGUMENT_COUNT,
                        "mysqli::select_db() expects exactly 1 argument, 0 given", null);
            }
            Link l = connected(c, self, "mysqli::select_db");
            if (l == null) {
                return false;
            }
            return selectDatabase(c, self, l, PhpStrings.toJava(PhpOps.toStr(args[0])), "mysqli::select_db");
        }));
        return methods;
    }

    private static PhpClassTemplate.MethodDecl method(PhpContext ctx, String name,
                                                      PhpExceptions.BuiltinMethod impl) {
        String lower = name.toLowerCase();
        var root = new PhpExceptions.BuiltinMethodRootNode(ctx.language(), name, impl);
        return new PhpClassTemplate.MethodDecl(name, lower, ctx.language().methodId(name),
                Visibility.PUBLIC, false, false, false, root.getCallTarget());
    }

    private static PhpClassTemplate resultTemplate(PhpContext ctx) {
        return ctx.language().mysqliResultTemplate(RESULT_PROPS);
    }

    private static PhpClassTemplate stmtTemplate(PhpContext ctx) {
        return ctx.language().mysqliStmtTemplate(STMT_PROPS);
    }

    private static List<PhpClassTemplate.PropertyDecl> linkProps() {
        List<PhpClassTemplate.PropertyDecl> props = new ArrayList<>();
        for (String p : LINK_PROPS) {
            props.add(prop(p, PhpNull.INSTANCE));
        }
        return props;
    }

    /** The built-in classes are declared the first time a request uses mysqli. */
    private static PhpClass phpClass(PhpContext ctx, PhpClassTemplate template) {
        PhpClass cls = ctx.lookupClass(template.name());
        return cls != null ? cls : ctx.declareClass(template);
    }

    private static PhpObject newObject(PhpContext ctx, PhpClassTemplate template) {
        PhpClass cls = phpClass(ctx, template);
        PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
        cls.populateDefaults(obj);
        return obj;
    }

    /** A mysqli_result object with its five properties set in declaration order. */
    private static PhpObject newResultObject(PhpContext ctx, Result r) {
        PhpObject obj = new PhpObject(phpClass(ctx, resultTemplate(ctx)), ctx.nextObjectId());
        set(obj, "current_field", (long) r.fieldPosition);
        set(obj, "field_count", (long) r.fields.size());
        set(obj, "lengths", PhpNull.INSTANCE);
        set(obj, "num_rows", (long) r.rows.size());
        set(obj, "type", 0L);
        return obj;
    }

    private static void set(DynamicObject obj, String key, Object value) {
        DynamicObject.PutNode.getUncached().execute(obj, key, value);
    }

    /** Copies the link's state into the object's public properties ($mysqli->connect_errno etc). */
    private static void sync(PhpContext ctx, PhpObject obj, Link link) {
        State st = state(ctx);
        set(obj, "affected_rows", link.affectedRows);
        set(obj, "client_info", CLIENT_INFO);
        set(obj, "client_version", CLIENT_VERSION);
        set(obj, "connect_errno", (long) st.connectErrno);
        set(obj, "connect_error", st.connectError == null ? PhpNull.INSTANCE : st.connectError);
        set(obj, "errno", (long) link.errno);
        set(obj, "error", link.error);
        PhpArray errors = new PhpArray();
        if (link.errno != 0) {
            PhpArray e = new PhpArray();
            e.put("errno", (long) link.errno);
            e.put("sqlstate", link.sqlstate);
            e.put("error", link.error);
            errors.append(e);
        }
        set(obj, "error_list", errors);
        set(obj, "field_count", (long) link.fieldCount);
        set(obj, "host_info", link.conn == null ? PhpNull.INSTANCE : link.host + " via TCP/IP");
        set(obj, "info", PhpNull.INSTANCE);
        set(obj, "insert_id", link.insertId);
        set(obj, "server_info", link.conn == null ? PhpNull.INSTANCE : link.serverInfo);
        set(obj, "server_version", link.conn == null ? PhpNull.INSTANCE : serverVersion(link.serverInfo));
        set(obj, "sqlstate", link.sqlstate);
        set(obj, "protocol_version", 10L);
        set(obj, "thread_id", PhpNull.INSTANCE);
        set(obj, "warning_count", 0L);
    }

    /**
     * Refreshes the properties a query can change. The connection-level properties (client and
     * server identity, protocol, host, thread, warning count) are set once by {@link #sync} at
     * connect and stay the same for the life of the link.
     */
    private static void syncQuery(PhpObject obj, Link link) {
        set(obj, "affected_rows", link.affectedRows);
        set(obj, "errno", (long) link.errno);
        set(obj, "error", link.error);
        PhpArray errors = new PhpArray();
        if (link.errno != 0) {
            PhpArray e = new PhpArray();
            e.put("errno", (long) link.errno);
            e.put("sqlstate", link.sqlstate);
            e.put("error", link.error);
            errors.append(e);
        }
        set(obj, "error_list", errors);
        set(obj, "field_count", (long) link.fieldCount);
        set(obj, "insert_id", link.insertId);
        set(obj, "sqlstate", link.sqlstate);
    }

    private static void syncResult(PhpObject obj, Result r) {
        set(obj, "current_field", (long) r.fieldPosition);
        set(obj, "field_count", (long) r.fields.size());
        set(obj, "num_rows", (long) r.rows.size());
        set(obj, "type", 0L);
    }

    private static PhpObject newPreparedObject(PhpContext ctx, Prepared p) {
        PhpObject obj = newObject(ctx, stmtTemplate(ctx));
        set(obj, "param_count", 0L);
        set(obj, "error_list", PhpNull.INSTANCE);
        syncPrepared(obj, p);
        return obj;
    }

    private static void syncPrepared(PhpObject obj, Prepared p) {
        set(obj, "affected_rows", p.affectedRows);
        set(obj, "errno", (long) p.errno);
        set(obj, "error", p.error);
        set(obj, "field_count", p.fieldCount);
        set(obj, "insert_id", p.insertId);
        set(obj, "num_rows", p.result == null ? 0L : (long) p.result.rows.size());
        set(obj, "sqlstate", p.sqlstate);
    }

    private static PhpObject stmtObject(PhpContext ctx, Object v, String fn) {
        if (v instanceof PhpObject o && o.phpClass().template() == stmtTemplate(ctx)) {
            return o;
        }
        throw new PhpError(PhpError.TYPE_ERROR, fn + "(): Argument #1 ($statement) must be of type mysqli_stmt, "
                + PhpOps.typeName(v) + " given", null);
    }

    private static Prepared prepared(PhpContext ctx, PhpObject obj, String fn) {
        Prepared p = state(ctx).statements.get(obj.id());
        if (p == null || p.closed) {
            throw new PhpError(p == null
                    ? "mysqli_stmt object is not fully initialized"
                    : "mysqli_stmt object is already closed");
        }
        return p;
    }

    static Object serverVersion(String info) {
        // "11.4.2-MariaDB-ubu2404" -> 110402
        String[] parts = info.split("[.-]");
        try {
            return Long.parseLong(parts[0]) * 10000 + Long.parseLong(parts[1]) * 100 + Long.parseLong(parts[2]);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------ argument helpers

    private static PhpObject linkObject(PhpContext ctx, Object v, String fn) {
        if (v instanceof PhpObject o && o.phpClass().template() == mysqliTemplate(ctx)) {
            return o;
        }
        throw new PhpError(PhpError.TYPE_ERROR, fn + "(): Argument #1 ($mysql) must be of type mysqli, "
                + PhpOps.typeName(v) + " given", null);
    }

    private static Link link(PhpContext ctx, Object v, String fn) {
        PhpObject o = linkObject(ctx, v, fn);
        Link l = state(ctx).links.get(o.id());
        if (l == null || l.closed) {
            throw new PhpError(l == null ? "mysqli object is not fully initialized" : "mysqli object is already closed");
        }
        return l;
    }

    /** A link that must be connected; a restored link connects here. Null when that failed. */
    private static Link connected(PhpContext ctx, Object v, String fn) {
        PhpObject o = linkObject(ctx, v, fn);
        Link l = link(ctx, v, fn);
        return ensureConnected(ctx, o, l, fn) ? l : null;
    }

    private static Result result(PhpContext ctx, Object v, String fn) {
        if (!(v instanceof PhpObject o) || o.phpClass().template() != resultTemplate(ctx)) {
            throw new PhpError(PhpError.TYPE_ERROR, fn + "(): Argument #1 ($result) must be of type mysqli_result, "
                    + PhpOps.typeName(v) + " given", null);
        }
        Result r = state(ctx).results.get(o.id());
        if (r == null || r.freed) {
            throw new PhpError("mysqli_result object is already closed");
        }
        return r;
    }

    private static String strArg(Object[] a, int i) {
        return i < a.length && a[i] != PhpNull.INSTANCE ? PhpOps.toStr(a[i]) : null;
    }

    // ------------------------------------------------------------------ blocking calls

    @FunctionalInterface
    interface SqlCall<T> {
        T run() throws SQLException;
    }

    /**
     * Runs a call that waits on the server with the CPU slot released. The call runs at most
     * once even if the scheduler retries the blocked section after an interrupt.
     */
    static <T> T blocking(PhpContext ctx, SqlCall<T> call) throws SQLException {
        return blocking(ctx.request().scheduler, call);
    }

    @SuppressWarnings("unchecked")
    private static <T> T blocking(GuestScheduler scheduler, SqlCall<T> call) throws SQLException {
        Object[] out = new Object[1];
        SQLException[] err = new SQLException[1];
        RuntimeException[] rte = new RuntimeException[1];
        boolean[] done = new boolean[1];
        scheduler.blocking(null, () -> {
            if (done[0]) {
                return;
            }
            done[0] = true;
            try {
                out[0] = call.run();
            } catch (SQLException e) {
                err[0] = e;
            } catch (RuntimeException e) {
                rte[0] = e;
            }
        });
        if (err[0] != null) {
            throw err[0];
        }
        if (rte[0] != null) {
            throw rte[0];
        }
        return (T) out[0];
    }

    // ------------------------------------------------------------------ errors

    static String message(SQLException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        return PhpStrings.fromJava(CONN_PREFIX.matcher(m).replaceFirst(""));
    }

    private static void fail(PhpContext ctx, Link link, SQLException e, String fn) {
        int code = e.getErrorCode();
        String msg = message(e);
        if (code == 0 && isConnectionLoss(e)) {
            code = 2006;
            msg = "MySQL server has gone away";
        }
        link.errno = code;
        link.error = msg;
        link.sqlstate = e.getSQLState() == null ? "HY000" : e.getSQLState();
        link.affectedRows = -1;
        report(ctx, fn, link.sqlstate, code, msg);
    }

    private static boolean isConnectionLoss(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.io.IOException || t instanceof java.sql.SQLNonTransientConnectionException) {
                return true;
            }
        }
        return false;
    }

    /** What mysqli_report asks for on an error: nothing, a warning, or an uncaught mysqli_sql_exception. */
    private static void report(PhpContext ctx, String fn, String sqlstate, int code, String msg) {
        long mode = state(ctx).reportMode;
        if ((mode & REPORT_ERROR) == 0) {
            return;
        }
        if ((mode & REPORT_STRICT) != 0) {
            com.oracle.truffle.api.nodes.Node site = ctx.currentBuiltinCallSite();
            String file = dev.phpruntime.runtime.PhpBacktrace.sourcePath(site);
            int line = dev.phpruntime.runtime.PhpBacktrace.sourceLine(site);
            dev.phpruntime.runtime.PhpBacktrace.LazyTrace trace =
                    dev.phpruntime.runtime.PhpBacktrace.captureFromBuiltin(site, fn);
            PhpObject exObj = dev.phpruntime.runtime.PhpExceptions.createBuiltinThrowable(
                    ctx, "mysqli_sql_exception", msg, code, file, line, trace);
            throw new dev.phpruntime.runtime.PhpException(exObj, site);
        }
        ctx.warning(fn + "(): (" + sqlstate + "/" + code + "): " + msg, 0);
    }

    private static void clearError(Link link) {
        link.errno = 0;
        link.error = "";
        link.sqlstate = "00000";
    }

    // ------------------------------------------------------------------ registration

    static void register() {
        // The driver logs every SQL error to the console; errors reach PHP through mysqli_error instead.
        if (System.getProperty("mariadb.logging.disable") == null) {
            System.setProperty("mariadb.logging.disable", "true");
        }
        Builtins.def("mysqli_init", 0, 0, (ctx, a) -> init(ctx));
        Builtins.def("mysqli_real_connect", 1, 8, MysqliFunctions::realConnect);
        Builtins.def("mysqli_connect", 0, 6, (ctx, a) -> {
            PhpObject obj = init(ctx);
            Object[] args = new Object[a.length + 1];
            args[0] = obj;
            System.arraycopy(a, 0, args, 1, a.length);
            return realConnect(ctx, args) == Boolean.TRUE ? obj : Boolean.FALSE;
        });
        Builtins.def("mysqli_connect_errno", 0, 0, (ctx, a) -> (long) state(ctx).connectErrno);
        Builtins.def("mysqli_connect_error", 0, 0, (ctx, a) -> {
            String e = state(ctx).connectError;
            return e == null ? PhpNull.INSTANCE : e;
        });
        Builtins.def("mysqli_report", 1, 1, (ctx, a) -> {
            state(ctx).reportMode = PhpOps.toLong(a[0]);
            return true;
        });
        Builtins.def("mysqli_select_db", 2, 2, (ctx, a) -> {
            Link l = connected(ctx, a[0], "mysqli_select_db");
            if (l == null) {
                return false;
            }
            String db = PhpStrings.toJava(PhpOps.toStr(a[1]));
            return selectDatabase(ctx, (PhpObject) a[0], l, db, "mysqli_select_db");
        });
        Builtins.def("mysqli_options", 3, 3, (ctx, a) -> {
            Link l = link(ctx, a[0], "mysqli_options");
            if (l.conn != null) {
                ctx.warning("mysqli_options(): Cannot set option after mysqli_real_connect", 0);
                return false;
            }
            long option = PhpOps.toLong(a[1]);
            Object value = a[2];
            l.options.put(option, value);
            if (option == 3L) { // MYSQLI_INIT_COMMAND
                l.initCommand = PhpOps.toStr(value);
            }
            if (recording(ctx)) {
                addSetup(l, MysqliSnapshot.Setup.option(option, PhpOps.toStr(value)));
            }
            return true;
        });
        Builtins.def("mysqli_autocommit", 2, 2, (ctx, a) -> {
            Link l = connected(ctx, a[0], "mysqli_autocommit");
            if (l == null) {
                return false;
            }
            boolean on = PhpOps.toBool(a[1]);
            try {
                blocking(ctx, () -> {
                    l.conn.setAutoCommit(on);
                    return null;
                });
                l.autocommit = on;
                l.ranSinceAutocommitOff = false;
                if (on) {
                    l.transaction = false;
                }
                if (recording(ctx)) {
                    addSetup(l, MysqliSnapshot.Setup.autocommit(on));
                }
                clearError(l);
                return true;
            } catch (SQLException e) {
                fail(ctx, l, e, "mysqli_autocommit");
                return false;
            } finally {
                sync(ctx, (PhpObject) a[0], l);
            }
        });
        Builtins.def("mysqli_set_charset", 2, 2, (ctx, a) -> {
            Link l = connected(ctx, a[0], "mysqli_set_charset");
            if (l == null) {
                return false;
            }
            String cs = PhpOps.toStr(a[1]);
            try {
                execute(ctx, l, "SET NAMES " + cs);
                l.charset = cs;
                if (recording(ctx)) {
                    addSetup(l, MysqliSnapshot.Setup.charset(cs));
                }
                clearError(l);
                return true;
            } catch (SQLException e) {
                fail(ctx, l, e, "mysqli_set_charset");
                return false;
            } finally {
                sync(ctx, (PhpObject) a[0], l);
            }
        });
        Builtins.def("mysqli_character_set_name", 1, 1, (ctx, a) -> link(ctx, a[0], "mysqli_character_set_name").charset);
        Builtins.def("mysqli_query", 2, 3, MysqliFunctions::query);
        Builtins.def("mysqli_real_query", 2, 2, (ctx, a) -> {
            Object r = query(ctx, a);
            return r != Boolean.FALSE;
        });
        Builtins.def("mysqli_real_escape_string", 2, 2, (ctx, a) -> {
            link(ctx, a[0], "mysqli_real_escape_string");
            return escape(PhpOps.toStr(a[1]));
        });
        Builtins.def("mysqli_escape_string", 2, 2, (ctx, a) -> {
            link(ctx, a[0], "mysqli_escape_string");
            return escape(PhpOps.toStr(a[1]));
        });
        Builtins.def("mysqli_error", 1, 1, (ctx, a) -> link(ctx, a[0], "mysqli_error").error);
        Builtins.def("mysqli_errno", 1, 1, (ctx, a) -> (long) link(ctx, a[0], "mysqli_errno").errno);
        Builtins.def("mysqli_sqlstate", 1, 1, (ctx, a) -> link(ctx, a[0], "mysqli_sqlstate").sqlstate);
        Builtins.def("mysqli_affected_rows", 1, 1, (ctx, a) -> link(ctx, a[0], "mysqli_affected_rows").affectedRows);
        Builtins.def("mysqli_insert_id", 1, 1, (ctx, a) -> link(ctx, a[0], "mysqli_insert_id").insertId);
        Builtins.def("mysqli_field_count", 1, 1, (ctx, a) -> (long) link(ctx, a[0], "mysqli_field_count").fieldCount);
        Builtins.def("mysqli_more_results", 1, 1, (ctx, a) -> {
            link(ctx, a[0], "mysqli_more_results");
            return false;
        });
        Builtins.def("mysqli_next_result", 1, 1, (ctx, a) -> {
            link(ctx, a[0], "mysqli_next_result");
            return false;
        });
        Builtins.def("mysqli_get_server_info", 1, 1, (ctx, a) -> link(ctx, a[0], "mysqli_get_server_info").serverInfo);
        Builtins.def("mysqli_get_server_version", 1, 1, (ctx, a) ->
                serverVersion(link(ctx, a[0], "mysqli_get_server_version").serverInfo));
        Builtins.def("mysqli_get_client_info", 0, 1, (ctx, a) -> CLIENT_INFO);
        Builtins.def("mysqli_get_client_version", 0, 0, (ctx, a) -> CLIENT_VERSION);
        Builtins.def("mysqli_get_host_info", 1, 1, (ctx, a) -> {
            String host = link(ctx, a[0], "mysqli_get_host_info").host;
            return host.isEmpty() ? PhpNull.INSTANCE : host + " via TCP/IP";
        });
        Builtins.def("mysqli_ping", 1, 1, (ctx, a) -> {
            Link l = connected(ctx, a[0], "mysqli_ping");
            if (l == null) {
                return false;
            }
            try {
                execute(ctx, l, "DO 1");
                return true;
            } catch (SQLException e) {
                fail(ctx, l, e, "mysqli_ping");
                return false;
            }
        });
        Builtins.def("mysqli_prepare", 2, 2, MysqliFunctions::prepare);
        Builtins.def("mysqli_stmt_execute", 1, 1, MysqliFunctions::stmtExecute);
        Builtins.def("mysqli_stmt_close", 1, 1, (ctx, a) -> {
            PhpObject o = stmtObject(ctx, a[0], "mysqli_stmt_close");
            Prepared p = prepared(ctx, o, "mysqli_stmt_close");
            if (p.ps != null) {
                try {
                    p.ps.close();
                } catch (SQLException ignored) {
                    // the statement is discarded either way
                }
            }
            p.closed = true;
            p.result = null;
            state(ctx).statements.remove(o.id());
            return true;
        });
        Builtins.def("mysqli_stmt_get_result", 1, 1, (ctx, a) -> {
            PhpObject o = stmtObject(ctx, a[0], "mysqli_stmt_get_result");
            Prepared p = prepared(ctx, o, "mysqli_stmt_get_result");
            if (p.result == null) {
                return false;
            }
            Result copy = new Result(p.result.fields, p.result.rows);
            copy.position = p.result.position;
            copy.fieldPosition = p.result.fieldPosition;
            PhpObject ro = newResultObject(ctx, copy);
            state(ctx).results.put(ro.id(), copy);
            return ro;
        });
        Builtins.def("mysqli_stmt_free_result", 1, 1, (ctx, a) -> {
            Prepared p = prepared(ctx, stmtObject(ctx, a[0], "mysqli_stmt_free_result"),
                    "mysqli_stmt_free_result");
            if (p.result != null) {
                p.result.freed = true;
                p.result = null;
            }
            return PhpNull.INSTANCE;
        });
        Builtins.def("mysqli_stmt_affected_rows", 1, 1, (ctx, a) ->
                prepared(ctx, stmtObject(ctx, a[0], "mysqli_stmt_affected_rows"),
                        "mysqli_stmt_affected_rows").affectedRows);
        Builtins.def("mysqli_stmt_insert_id", 1, 1, (ctx, a) ->
                prepared(ctx, stmtObject(ctx, a[0], "mysqli_stmt_insert_id"),
                        "mysqli_stmt_insert_id").insertId);
        Builtins.def("mysqli_stmt_field_count", 1, 1, (ctx, a) ->
                prepared(ctx, stmtObject(ctx, a[0], "mysqli_stmt_field_count"),
                        "mysqli_stmt_field_count").fieldCount);
        Builtins.def("mysqli_stmt_num_rows", 1, 1, (ctx, a) -> {
            Prepared p = prepared(ctx, stmtObject(ctx, a[0], "mysqli_stmt_num_rows"),
                    "mysqli_stmt_num_rows");
            return p.result == null ? 0L : (long) p.result.rows.size();
        });
        Builtins.def("mysqli_stmt_errno", 1, 1, (ctx, a) ->
                (long) prepared(ctx, stmtObject(ctx, a[0], "mysqli_stmt_errno"), "mysqli_stmt_errno").errno);
        Builtins.def("mysqli_stmt_error", 1, 1, (ctx, a) ->
                prepared(ctx, stmtObject(ctx, a[0], "mysqli_stmt_error"), "mysqli_stmt_error").error);
        Builtins.def("mysqli_stmt_sqlstate", 1, 1, (ctx, a) ->
                prepared(ctx, stmtObject(ctx, a[0], "mysqli_stmt_sqlstate"), "mysqli_stmt_sqlstate").sqlstate);
        Builtins.def("mysqli_close", 1, 1, (ctx, a) -> {
            Link l = link(ctx, a[0], "mysqli_close");
            try {
                l.close();
            } catch (SQLException ignored) {
                // closing a broken connection still closes it
            }
            ctx.request().resources.remove(l);
            return true;
        });
        Builtins.def("mysqli_num_rows", 1, 1, (ctx, a) -> (long) result(ctx, a[0], "mysqli_num_rows").rows.size());
        Builtins.def("mysqli_num_fields", 1, 1, (ctx, a) -> (long) result(ctx, a[0], "mysqli_num_fields").fields.size());
        Builtins.def("mysqli_fetch_array", 1, 2, (ctx, a) ->
                fetch(result(ctx, a[0], "mysqli_fetch_array"), a.length > 1 ? PhpOps.toLong(a[1]) : BOTH));
        Builtins.def("mysqli_fetch_assoc", 1, 1, (ctx, a) -> fetch(result(ctx, a[0], "mysqli_fetch_assoc"), ASSOC));
        Builtins.def("mysqli_fetch_row", 1, 1, (ctx, a) -> fetch(result(ctx, a[0], "mysqli_fetch_row"), NUM));
        Builtins.def("mysqli_fetch_all", 1, 2, (ctx, a) -> {
            Result r = result(ctx, a[0], "mysqli_fetch_all");
            long mode = a.length > 1 ? PhpOps.toLong(a[1]) : NUM;
            PhpArray all = new PhpArray();
            Object row;
            while ((row = fetch(r, mode)) != PhpNull.INSTANCE) {
                all.append(row);
            }
            return all;
        });
        Builtins.def("mysqli_fetch_object", 1, 3, (ctx, a) -> {
            Result r = result(ctx, a[0], "mysqli_fetch_object");
            if (a.length > 1 && a[1] != PhpNull.INSTANCE && !PhpOps.toStr(a[1]).equalsIgnoreCase("stdClass")) {
                // TODO: a class name (and constructor arguments) other than stdClass.
                throw new PhpError("mysqli_fetch_object() with a class other than stdClass is not supported yet");
            }
            Object row = fetch(r, ASSOC);
            if (!(row instanceof PhpArray arr)) {
                return PhpNull.INSTANCE;
            }
            PhpObject obj = new PhpObject(ctx.stdClass(), ctx.nextObjectId());
            arr.forEachEntry((key, value) -> set(obj, PhpOps.toStr(key), value));
            return obj;
        });
        Builtins.def("mysqli_fetch_field", 1, 1, (ctx, a) -> {
            Result r = result(ctx, a[0], "mysqli_fetch_field");
            if (r.fieldPosition >= r.fields.size()) {
                return false;
            }
            Object f = fieldObject(ctx, r.fields.get(r.fieldPosition++));
            syncResult((PhpObject) a[0], r);
            return f;
        });
        Builtins.def("mysqli_fetch_field_direct", 2, 2, (ctx, a) -> {
            Result r = result(ctx, a[0], "mysqli_fetch_field_direct");
            long i = PhpOps.toLong(a[1]);
            if (i < 0 || i >= r.fields.size()) {
                return false;
            }
            return fieldObject(ctx, r.fields.get((int) i));
        });
        Builtins.def("mysqli_fetch_fields", 1, 1, (ctx, a) -> {
            Result r = result(ctx, a[0], "mysqli_fetch_fields");
            PhpArray all = new PhpArray();
            for (FieldInfo f : r.fields) {
                all.append(fieldObject(ctx, f));
            }
            return all;
        });
        Builtins.def("mysqli_field_seek", 2, 2, (ctx, a) -> {
            Result r = result(ctx, a[0], "mysqli_field_seek");
            long i = PhpOps.toLong(a[1]);
            if (i < 0 || i >= r.fields.size()) {
                throw new PhpError("ValueError", "mysqli_field_seek(): Argument #2 ($index) must be greater than or equal to 0 and less than the number of fields", null);
            }
            r.fieldPosition = (int) i;
            syncResult((PhpObject) a[0], r);
            return true;
        });
        Builtins.def("mysqli_data_seek", 2, 2, (ctx, a) -> {
            Result r = result(ctx, a[0], "mysqli_data_seek");
            long i = PhpOps.toLong(a[1]);
            if (i < 0 || i >= r.rows.size()) {
                return false;
            }
            r.position = (int) i;
            return true;
        });
        Builtins.def("mysqli_free_result", 1, 1, (ctx, a) -> {
            Result r = result(ctx, a[0], "mysqli_free_result");
            r.freed = true;
            state(ctx).results.remove(((PhpObject) a[0]).id());
            return PhpNull.INSTANCE;
        });
    }

    // ------------------------------------------------------------------ connect

    private static PhpObject init(PhpContext ctx) {
        PhpObject obj = newObject(ctx, mysqliTemplate(ctx));
        Link link = new Link();
        link.object = obj;
        state(ctx).links.put(obj.id(), link);
        set(obj, "client_info", CLIENT_INFO);
        set(obj, "client_version", CLIENT_VERSION);
        return obj;
    }

    private static Object realConnect(PhpContext ctx, Object[] a) {
        PhpObject obj = linkObject(ctx, a[0], "mysqli_real_connect");
        State st = state(ctx);
        Link link = st.links.get(obj.id());
        if (link == null) {
            link = new Link();
            st.links.put(obj.id(), link);
        }
        String host = strArg(a, 1);
        String user = strArg(a, 2);
        String password = strArg(a, 3);
        String db = strArg(a, 4);
        long port = a.length > 5 && a[5] != PhpNull.INSTANCE ? PhpOps.toLong(a[5]) : 3306;
        if (port == 0) {
            port = 3306;
        }
        if (host == null || host.isEmpty()) {
            host = "localhost";
        }
        if (host.startsWith("p:")) {
            host = host.substring(2);
        }
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon && host.substring(colon + 1).matches("\\d+")) {
            port = Long.parseLong(host.substring(colon + 1));
            host = host.substring(0, colon);
        }
        // mysqlnd connects to "localhost" through the Unix socket; this driver setup speaks TCP only.
        String tcpHost = host.equals("localhost") ? "127.0.0.1" : host;
        if (tcpHost.startsWith("[") && tcpHost.endsWith("]")) {
            tcpHost = tcpHost.substring(1, tcpHost.length() - 1);
        }
        String javaHost = PhpStrings.toJava(tcpHost);
        String url = "jdbc:mariadb://" + (javaHost.contains(":") ? "[" + javaHost + "]" : javaHost) + ":" + port + "/"
                + (db == null ? "" : PhpStrings.toJava(db));
        Properties props = new Properties();
        if (user != null) {
            props.setProperty("user", PhpStrings.toJava(user));
        }
        if (password != null) {
            props.setProperty("password", PhpStrings.toJava(password));
        }
        // Report changed rows (not matched rows) and leave every value as the server's text.
        props.setProperty("useAffectedRows", "true");
        props.setProperty("tinyInt1isBit", "false");
        props.setProperty("yearIsDateType", "false");
        props.setProperty("transformedBitIsBoolean", "false");
        props.setProperty("connectTimeout", "10000");
        props.setProperty("permitMysqlScheme", "true");
        // php's connection leaves the session at the server's default mode. The driver would
        // otherwise append STRICT_TRANS_TABLES, track session variables, and add IGNORE_SPACE
        // through its CLIENT_IGNORE_SPACE capability; the explicit DEFAULT undoes the last one.
        props.setProperty("jdbcCompliantTruncation", "false");
        props.setProperty("disableSessionTracking", "true");
        props.setProperty("sessionVariables", "sql_mode=DEFAULT");
        Object connectTimeout = link.options.get(0L); // MYSQLI_OPT_CONNECT_TIMEOUT, in seconds
        if (connectTimeout != null) {
            props.setProperty("connectTimeout", Long.toString(PhpOps.toLong(connectTimeout) * 1000L));
        }
        Object readTimeout = link.options.get(11L); // MYSQLI_OPT_READ_TIMEOUT, in seconds
        if (readTimeout != null) {
            props.setProperty("socketTimeout", Long.toString(PhpOps.toLong(readTimeout) * 1000L));
        }
        String initCommand = link.initCommand;
        final Link l = link;
        boolean pooling = Pool.enabled();
        Properties driverProps = new Properties();
        driverProps.putAll(props);
        if (pooling) {
            // Makes Connection.reset() send COM_RESET_CONNECTION, which clears the session
            // state that a fresh connection would not have.
            driverProps.setProperty("useResetConnection", "true");
        }
        final PoolKey key = pooling ? new PoolKey(url,
                user == null ? null : PhpStrings.toJava(user),
                password == null ? null : PhpStrings.toJava(password),
                propertyMap(driverProps)) : null;
        try {
            Opened opened = blocking(ctx, () -> open(key, url, driverProps, pooling, pooling));
            l.conn = opened.connection();
            l.closed = false;
            l.host = host;
            l.serverInfo = PhpStrings.fromJava(opened.serverInfo());
            l.poolServerInfo = opened.serverInfo();
            l.freshSession = opened.session();
            l.url = url;
            l.driverProps = driverProps;
            l.logicalCatalog = databaseOrNull(db);
            l.physicalCatalog = opened.catalog();
            l.key = key;
            l.poolable = l.freshSession != null;
            l.charset = l.freshSession == null ? "utf8mb4" : l.freshSession.charsetClient();
            l.restored = false;
            l.setup = null;
            l.refusals.clear();
            l.transaction = false;
            l.autocommit = true;
            l.ranSinceAutocommitOff = false;
            l.object = obj;
            if (initCommand != null && !initCommand.isEmpty()) {
                runSetup(ctx, l, initCommand);
            }
            st.connectErrno = 0;
            st.connectError = null;
            clearError(l);
            ctx.request().resources.add(l);
            sync(ctx, obj, l);
            return true;
        } catch (SQLException e) {
            int code = e.getErrorCode();
            String msg = message(e);
            if (code == 0) {
                code = 2002;
                msg = connectFailureMessage(e, host);
            }
            st.connectErrno = code;
            st.connectError = msg;
            l.errno = code;
            l.error = msg;
            l.sqlstate = "HY000"; // mysqlnd reports every connect failure as HY000
            sync(ctx, obj, l);
            if ((st.reportMode & (REPORT_ERROR | REPORT_STRICT)) == (REPORT_ERROR | REPORT_STRICT)) {
                report(ctx, "mysqli_real_connect", l.sqlstate, code, msg);
            } else {
                // PHP warns about a failed connect whatever the report mode, unless it throws
                ctx.warning("mysqli_real_connect(): (" + l.sqlstate + "/" + code + "): " + msg, 0);
            }
            return false;
        }
    }

    /** A connection ready for one request, either newly opened or taken from the pool. */
    private record Opened(Connection connection, String serverInfo, SessionState session,
                          String catalog, boolean pooled) {
    }

    private static Opened open(PoolKey key, String url, Properties props, boolean allowPool,
                              boolean takeIdle) throws SQLException {
        if (allowPool && takeIdle) {
            Idle idle = Pool.take(key);
            if (idle != null) {
                return new Opened(idle.connection(), idle.serverInfo(), idle.session(), idle.catalog(), true);
            }
        }
        Connection conn = new org.mariadb.jdbc.Driver().connect(url, props);
        if (conn == null) {
            throw new SQLException("Unsupported connection URL", "HY000", 2002);
        }
        OPENED_CONNECTIONS.incrementAndGet();
        try {
            // The handshake already carries the server version; querying it again costs a round trip.
            String serverInfo = conn instanceof org.mariadb.jdbc.Connection mariadb
                    ? mariadb.getContext().getVersion().getVersion()
                    : conn.getMetaData().getDatabaseProductVersion();
            SessionState session = allowPool && supportsReset(serverInfo) ? readSession(conn) : null;
            // The session starts in the URL's database; asking the driver would send SELECT DATABASE().
            return new Opened(conn, serverInfo, session, urlDatabase(url), false);
        } catch (SQLException | RuntimeException e) {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // the connect already failed
            }
            throw e;
        }
    }

    /** A connect-time database name; an empty name means no database, as in the handshake. */
    /** The database a jdbc:mariadb://host:port/db URL connects to, or null when it names none. */
    private static String urlDatabase(String url) {
        String db = url.substring(url.indexOf('/', "jdbc:mariadb://".length()) + 1);
        return db.isEmpty() ? null : db;
    }

    private static String databaseOrNull(String db) {
        return db == null || db.isEmpty() ? null : PhpStrings.toJava(db);
    }

    private static Map<String, String> propertyMap(Properties props) {
        Map<String, String> map = new HashMap<>();
        for (String name : props.stringPropertyNames()) {
            map.put(name, props.getProperty(name));
        }
        return Map.copyOf(map);
    }

    static String connectFailureMessage(SQLException e, String host) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnknownHostException) {
                return "php_network_getaddresses: getaddrinfo for " + host + " failed: Name or service not known";
            }
            if (t instanceof ConnectException) {
                return "Connection refused";
            }
            if (t instanceof java.net.SocketTimeoutException) {
                return "Connection timed out";
            }
        }
        return message(e);
    }

    // ------------------------------------------------------------------ queries

    /**
     * Every operation that talks to the server goes through here first. A pooled connection
     * can carry a database PHP never selected (reset keeps the database the session had), so
     * when the logical and physical catalogs differ, the link gets a connection made exactly
     * like a fresh connect with the URL it was opened with, and the pooled one is discarded.
     * The link's error state, insert id, field count and charset are reset as a fresh connect
     * would set them; the caller then performs its operation on the new session.
     *
     * <p>Statements that cannot name a table or ask for the current database (character-set
     * and session-variable setup) are the one exception: they run on the pooled session,
     * whose other session state was already reset to a fresh one, so PHP cannot tell that the
     * database differs. WordPress runs exactly such setup statements before it selects its
     * database, and replacing the connection for them would discard every WordPress link
     * before it could be reused. {@link #databaseIndependent} recognises the narrow forms.
     */
    private static void beforeServerOperation(PhpContext ctx, Link l, String sql) throws SQLException {
        if (Objects.equals(l.logicalCatalog, l.physicalCatalog) || databaseIndependent(sql)) {
            return;
        }
        blocking(ctx, () -> {
            Opened opened = open(l.key, l.url, l.driverProps, l.key != null, false);
            Connection old = l.conn;
            l.conn = opened.connection();
            l.physicalCatalog = opened.catalog();
            l.serverInfo = PhpStrings.fromJava(opened.serverInfo());
            l.poolServerInfo = opened.serverInfo();
            l.freshSession = opened.session();
            l.poolable = l.freshSession != null;
            l.charset = l.freshSession == null ? "utf8mb4" : l.freshSession.charsetClient();
            clearError(l);
            l.affectedRows = 0;
            l.insertId = 0;
            l.fieldCount = 0;
            if (old != null) {
                try {
                    old.close();
                } catch (SQLException ignored) {
                    // the request cannot use it any more; it is discarded either way
                }
            }
            return null;
        });
    }

    /** The session-setup statements that cannot name a table or reveal the current database. */
    private static final Pattern SET_NAMES = Pattern.compile(
            "(?is)^SET\\s+NAMES\\s+(?:'[^']*'|\"[^\"]*\"|[A-Za-z0-9_]+)"
                    + "(?:\\s+COLLATE\\s+(?:'[^']*'|\"[^\"]*\"|[A-Za-z0-9_]+))?$");
    private static final Pattern SET_SESSION = Pattern.compile(
            "(?is)^SET\\s+(?:SESSION\\s+|@@SESSION\\.|@@)[A-Za-z0-9_.]+\\s*=\\s*"
                    + "(?:'(?:[^'\\\\]|\\\\.|'')*'|\"[^\"]*\"|[A-Za-z0-9_,.+-]+)$");
    private static final Pattern SELECT_SESSION_VARS = Pattern.compile(
            "(?is)^SELECT\\s+@@[A-Za-z0-9_.]+(?:\\s*,\\s*@@[A-Za-z0-9_.]+)*$");

    /**
     * True for the session-setup statements whose effect does not depend on the default
     * database: SET NAMES, setting a session system variable to a literal, and reading
     * session system variables. Deliberately narrow: a FROM clause, a subquery, a function
     * call or a DATABASE()/SCHEMA() call takes the statement out of this set.
     */
    private static boolean databaseIndependent(String sql) {
        String s = sql.stripLeading();
        if (s.toLowerCase(java.util.Locale.ROOT).contains("_database")) {
            // @@character_set_database and @@collation_database follow the selected database.
            return false;
        }
        return SET_NAMES.matcher(s).matches() || SET_SESSION.matcher(s).matches()
                || SELECT_SESSION_VARS.matcher(s).matches();
    }

    private static void execute(PhpContext ctx, Link l, String sql) throws SQLException {
        beforeServerOperation(ctx, l, sql);
        String javaSql = MysqliSql.toJava(sql);
        blocking(ctx, () -> {
            try (Statement st = l.conn.createStatement()) {
                st.execute(javaSql);
            }
            return null;
        });
    }

    /**
     * PHP's select-database operation. Asking for the database the JDBC session already has
     * changes only PHP's view; anything else runs the driver's change-database command and
     * moves both catalogs.
     */
    private static boolean selectDatabase(PhpContext ctx, PhpObject obj, Link l, String db, String fn) {
        try {
            if (Objects.equals(db, l.physicalCatalog)) {
                l.logicalCatalog = db;
                if (recording(ctx)) {
                    addSetup(l, MysqliSnapshot.Setup.database(db));
                }
                clearError(l);
                sync(ctx, obj, l);
                return true;
            }
            blocking(ctx, () -> {
                l.conn.setCatalog(db);
                return null;
            });
            // COM_INIT_DB succeeded, so the session is in db; asking would cost a SELECT DATABASE().
            l.logicalCatalog = db;
            l.physicalCatalog = db;
            if (recording(ctx)) {
                addSetup(l, MysqliSnapshot.Setup.database(db));
            }
            clearError(l);
            sync(ctx, obj, l);
            return true;
        } catch (SQLException e) {
            fail(ctx, l, e, fn);
            sync(ctx, obj, l);
            return false;
        }
    }

    /** Result of running one statement, built on the blocking side. */
    private record Outcome(Result result, long updateCount, long insertId) {
    }

    private static Object query(PhpContext ctx, Object[] a) {
        PhpObject obj = linkObject(ctx, a[0], "mysqli_query");
        Link l = link(ctx, obj, "mysqli_query");
        String sql = PhpOps.toStr(a[1]);
        if (sql.isEmpty()) {
            throw new PhpError("ValueError", "mysqli_query(): Argument #2 ($query) cannot be empty", null);
        }
        if (writesStatement(sql)) {
            SnapshotGeneration.bump();
        }
        if (!ensureConnected(ctx, obj, l, "mysqli_query")) {
            return false;
        }
        try {
            beforeServerOperation(ctx, l, sql);
            String javaSql = MysqliSql.toJava(sql);
            Outcome out = blocking(ctx, () -> {
                try (Statement st = l.conn.createStatement()) {
                    boolean hasResult = st.execute(javaSql, Statement.RETURN_GENERATED_KEYS);
                    if (hasResult) {
                        try (ResultSet rs = st.getResultSet()) {
                            return new Outcome(read(rs), -1, 0);
                        }
                    }
                    long count = st.getLargeUpdateCount();
                    long id = 0;
                    try (ResultSet keys = st.getGeneratedKeys()) {
                        if (keys != null && keys.next()) {
                            id = keys.getLong(1);
                        }
                    }
                    return new Outcome(null, count, id);
                }
            });
            clearError(l);
            recordStatement(ctx, l, sql);
            if (out.result() != null) {
                Result r = out.result();
                l.affectedRows = r.rows.size();
                l.insertId = 0;
                l.fieldCount = r.fields.size();
                PhpObject ro = newResultObject(ctx, r);
                state(ctx).results.put(ro.id(), r);
                syncQuery(obj, l);
                return ro;
            }
            l.affectedRows = out.updateCount();
            l.insertId = out.insertId();
            l.fieldCount = 0;
            syncQuery(obj, l);
            return true;
        } catch (SQLException e) {
            fail(ctx, l, e, "mysqli_query");
            syncQuery(obj, l);
            return false;
        }
    }

    // ------------------------------------------------------------------ prepared statements

    private static Object prepare(PhpContext ctx, Object[] a) {
        PhpObject obj = linkObject(ctx, a[0], "mysqli_prepare");
        Link l = link(ctx, obj, "mysqli_prepare");
        String sql = PhpOps.toStr(a[1]);
        if (sql.isEmpty()) {
            throw new PhpError("ValueError", "mysqli_prepare(): Argument #2 ($query) cannot be empty", null);
        }
        if (!ensureConnected(ctx, obj, l, "mysqli_prepare")) {
            return false;
        }
        Prepared p = new Prepared();
        p.linkId = obj.id();
        p.sql = sql;
        p.bump = writesStatement(sql);
        try {
            beforeServerOperation(ctx, l, sql);
            p.ps = blocking(ctx, () -> l.conn.prepareStatement(MysqliSql.toJava(sql),
                    Statement.RETURN_GENERATED_KEYS));
        } catch (SQLException e) {
            fail(ctx, l, e, "mysqli_prepare");
            syncQuery(obj, l);
            return false;
        }
        clearError(l);
        PhpObject st = newPreparedObject(ctx, p);
        state(ctx).statements.put(st.id(), p);
        return st;
    }

    private static Object stmtExecute(PhpContext ctx, Object[] a) {
        PhpObject obj = stmtObject(ctx, a[0], "mysqli_stmt_execute");
        Prepared p = prepared(ctx, obj, "mysqli_stmt_execute");
        State st = state(ctx);
        Link l = st.links.get(p.linkId);
        if (l == null || l.closed) {
            throw new PhpError("mysqli object is already closed");
        }
        if (!ensureConnected(ctx, l.object, l, "mysqli_stmt_execute")) {
            return false;
        }
        if (p.bump) {
            SnapshotGeneration.bump();
        }
        try {
            beforeServerOperation(ctx, l, p.sql);
            Outcome out = blocking(ctx, () -> {
                boolean has = p.ps.execute();
                if (has) {
                    try (ResultSet rs = p.ps.getResultSet()) {
                        return new Outcome(read(rs), -1, 0);
                    }
                }
                long count = p.ps.getLargeUpdateCount();
                long id = 0;
                try (ResultSet keys = p.ps.getGeneratedKeys()) {
                    if (keys != null && keys.next()) {
                        id = keys.getLong(1);
                    }
                }
                return new Outcome(null, count, id);
            });
            clearError(l);
            p.errno = 0;
            p.error = "";
            p.sqlstate = "00000";
            if (out.result() != null) {
                p.result = out.result();
                p.affectedRows = out.result().rows.size();
                p.insertId = 0;
                p.fieldCount = out.result().fields.size();
            } else {
                p.result = null;
                p.affectedRows = out.updateCount();
                p.insertId = out.insertId();
                p.fieldCount = 0;
            }
            l.affectedRows = p.affectedRows;
            l.insertId = p.insertId;
            l.fieldCount = (int) p.fieldCount;
            if (l.object != null) {
                syncQuery(l.object, l);
            }
            syncPrepared(obj, p);
            return true;
        } catch (SQLException e) {
            fail(ctx, l, e, "mysqli_stmt_execute");
            p.errno = l.errno;
            p.error = l.error;
            p.sqlstate = l.sqlstate;
            p.affectedRows = -1;
            if (l.object != null) {
                syncQuery(l.object, l);
            }
            syncPrepared(obj, p);
            return false;
        }
    }

    private static final Field DRIVER_METADATA =
            driverField("org.mariadb.jdbc.client.result.Result", "metadataList");
    private static final Field DRIVER_CHARSET =
            driverField("org.mariadb.jdbc.message.server.ColumnDefinitionPacket", "charset");

    private static Field driverField(String className, String fieldName) {
        try {
            Field f = Class.forName(className).getDeclaredField(fieldName);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The driver's own column descriptions, which carry MySQL's type codes and flags. Reading
     * them from the result avoids a fresh JDBC metadata object for every query; null when the
     * driver is not MariaDB Connector/J or its internals moved.
     */
    static Object[] driverColumns(ResultSet rs) {
        Field f = DRIVER_METADATA;
        if (f == null || !(rs instanceof org.mariadb.jdbc.client.result.Result result)) {
            return null;
        }
        try {
            return (Object[]) f.get(result);
        } catch (IllegalAccessException | RuntimeException e) {
            return null;
        }
    }

    private static Result read(ResultSet rs) throws SQLException {
        Object[] cols = driverColumns(rs);
        ResultSetMetaData md = null;
        int n;
        if (cols != null) {
            n = cols.length;
        } else {
            md = rs.getMetaData();
            n = md.getColumnCount();
        }
        List<FieldInfo> fields = new ArrayList<>(n);
        boolean[] bit = new boolean[n];
        boolean[] bytes = new boolean[n];
        for (int i = 0; i < n; i++) {
            FieldInfo f;
            if (cols != null && cols[i] instanceof org.mariadb.jdbc.client.Column c) {
                f = field(c);
            } else {
                if (md == null) {
                    md = rs.getMetaData();
                }
                f = field(md, i + 1);
            }
            fields.add(f);
            long t = f.type();
            bit[i] = t == 16;
            bytes[i] = isByteType(t);
        }
        List<Object[]> rows = new ArrayList<>();
        while (rs.next()) {
            Object[] row = new Object[n];
            for (int i = 0; i < n; i++) {
                if (bit[i]) {
                    // mysqlnd returns BIT values as decimal strings
                    byte[] b = rs.getBytes(i + 1);
                    if (b == null) {
                        row[i] = PhpNull.INSTANCE;
                    } else {
                        long v = 0;
                        for (byte x : b) {
                            v = (v << 8) | (x & 0xFF);
                        }
                        row[i] = Long.toUnsignedString(v);
                    }
                } else if (bytes[i]) {
                    byte[] b = rs.getBytes(i + 1);
                    row[i] = b == null ? PhpNull.INSTANCE : PhpStrings.of(b);
                } else {
                    // Numbers, dates and decimals: the text protocol's ASCII form, as mysqlnd returns.
                    String v = rs.getString(i + 1);
                    row[i] = v == null ? PhpNull.INSTANCE : PhpStrings.fromJava(v);
                }
            }
            rows.add(row);
        }
        return new Result(fields, rows);
    }

    static FieldInfo field(org.mariadb.jdbc.client.Column c) {
        long charset = 0;
        try {
            Field f = DRIVER_CHARSET;
            if (f != null) {
                charset = ((Number) f.get(c)).longValue();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // leave 0
        }
        long type = c.getType().get();
        long length = c.getColumnLength() & 0xFFFFFFFFL;
        long flags = c.getFlags() & 0xFFFF;
        if (isNumericType(type, length)) {
            flags |= 32768; // mysqlnd sets NUM_FLAG itself
        }
        return new FieldInfo(PhpStrings.fromJava(c.getColumnAlias()), PhpStrings.fromJava(c.getColumnName()),
                PhpStrings.fromJava(c.getTableAlias()), PhpStrings.fromJava(c.getTable()), PhpStrings.fromJava(c.getSchema()),
                length, charset, flags, type, c.getDecimals());
    }

    /** When mysqlnd adds NUM_FLAG: types up to INT24 except modern TIMESTAMP, and YEAR(4). */
    /** String, blob, JSON, enum, set and geometry columns: read as raw bytes (binary-safe). */
    private static boolean isByteType(long t) {
        return t == 15 || t == 245 || (t >= 247 && t <= 255);
    }

    private static boolean isNumericType(long t, long length) {
        return (t <= 9 && (t != 7 || length == 14 || length == 8)) || (t == 13 && length == 4);
    }

    static FieldInfo field(ResultSetMetaData md, int i) throws SQLException {
        return new FieldInfo(PhpStrings.fromJava(md.getColumnLabel(i)), PhpStrings.fromJava(md.getColumnName(i)),
                PhpStrings.fromJava(md.getTableName(i)), PhpStrings.fromJava(md.getTableName(i)),
                PhpStrings.fromJava(md.getCatalogName(i)), md.getPrecision(i), 0, md.isNullable(i) == ResultSetMetaData.columnNoNulls ? 1 : 0,
                253, md.getScale(i));
    }

    private static Object fetch(Result r, long mode) {
        if (r.position >= r.rows.size()) {
            return PhpNull.INSTANCE;
        }
        if (mode < ASSOC || mode > BOTH) {
            throw new PhpError("ValueError", "mysqli_fetch_array(): Argument #2 ($mode) must be one of MYSQLI_NUM, MYSQLI_ASSOC, or MYSQLI_BOTH", null);
        }
        Object[] row = r.rows.get(r.position++);
        PhpArray arr = new PhpArray();
        for (int i = 0; i < row.length; i++) {
            if ((mode & NUM) != 0) {
                arr.put((long) i, row[i]);
            }
            if ((mode & ASSOC) != 0) {
                arr.put(r.fields.get(i).name(), row[i]);
            }
        }
        return arr;
    }

    private static PhpObject fieldObject(PhpContext ctx, FieldInfo f) {
        PhpObject obj = new PhpObject(ctx.stdClass(), ctx.nextObjectId());
        set(obj, "name", f.name());
        set(obj, "orgname", f.orgname());
        set(obj, "table", f.table());
        set(obj, "orgtable", f.orgtable());
        set(obj, "def", "");
        set(obj, "db", f.db());
        set(obj, "catalog", "def");
        set(obj, "max_length", 0L);
        set(obj, "length", f.length());
        set(obj, "charsetnr", f.charsetnr());
        set(obj, "flags", f.flags());
        set(obj, "type", f.type());
        set(obj, "decimals", f.decimals());
        return obj;
    }

    // ------------------------------------------------------------------ escaping

    /** mysqli_real_escape_string for a connection without NO_BACKSLASH_ESCAPES. */
    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case 0 -> sb.append("\\0");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\\' -> sb.append("\\\\");
                case '\'' -> sb.append("\\'");
                case '"' -> sb.append("\\\"");
                case 0x1A -> sb.append("\\Z");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
