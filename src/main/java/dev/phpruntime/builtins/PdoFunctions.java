package dev.phpruntime.builtins;

import com.oracle.truffle.api.object.DynamicObject;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.call.DispatchCallNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpBacktrace;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpException;
import dev.phpruntime.runtime.PhpExceptions;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;
import dev.phpruntime.runtime.PhpStrings;
import dev.phpruntime.runtime.Visibility;

import java.io.IOException;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import static dev.phpruntime.builtins.PdoClasses.*;

/**
 * The PDO API over JDBC (MariaDB Connector/J), matching php 8.5 with pdo_mysql and mysqlnd.
 *
 * <p>Connections and statements are per-request state keyed by object id, exactly like mysqli's
 * state. Every call that waits on the server runs through {@code MysqliFunctions.blocking}, so a
 * slow query gives its CPU slot to other requests. Results are read fully when the statement
 * runs, which is what PDO does by default (ATTR_USE_BUFFERED_QUERY).
 *
 * <p>Emulated prepares (pdo_mysql's default) substitute escaped literals into the SQL text and
 * run it as a plain statement, so {@code prepare()} never contacts the server and errors appear
 * at {@code execute()}. Native prepares use a JDBC prepared statement; both read values through
 * mysqlnd's conversion rules: integer and float columns become PHP ints and floats, everything
 * else strings, unless ATTR_STRINGIFY_FETCHES asks for strings.
 */
final class PdoFunctions {

    private PdoFunctions() {
    }

    private static final String CLIENT_VERSION = "mysqlnd 8.5.0";
    private static final String DEFAULT_SOCKET = "/var/run/mysqld/mysqld.sock";

    // ------------------------------------------------------------------ state

    /** Per-request PDO state. */
    static final class State {
        final Map<Long, Dbh> connections = new HashMap<>();
        final Map<Long, Stmt> statements = new HashMap<>();
        final Map<Long, IteratorState> iterators = new HashMap<>();
    }

    /** One PDO object's connection and attributes. */
    static final class Dbh {
        Connection conn;
        long errmode = ERRMODE_EXCEPTION;
        long defaultFetch = FETCH_BOTH;
        long attrCase = CASE_NATURAL;
        long oracleNulls = NULL_NATURAL;
        boolean stringify;
        boolean emulate = true;
        boolean persistent;
        long timeout = 28800;
        long defaultStrParam = PARAM_STR_CHAR;
        boolean buffered = true;
        boolean autocommit = true;
        boolean explicitTransaction;
        boolean implicitTransaction;
        String statementClass = "PDOStatement";
        final List<Object> statementClassArgs = new ArrayList<>();
        String lastInsertId = "0";
        String serverVersion = "";
        String hostInfo = "";
        String errorCode = "00000";
        long errorErrno;
        String errorMsg;
        boolean hasError;
        /** The URL and properties used to open the connection, for nothing yet but diagnostics. */
        String url;
        Properties props;
    }

    static final class Param {
        long type;
        Object value;
        PhpReference ref;

        Param(long type, Object value, PhpReference ref) {
            this.type = type;
            this.value = value;
            this.ref = ref;
        }

        Object read() {
            return ref != null ? ref.getValue() : value;
        }
    }

    static final class BoundColumn {
        int index;
        String name;
        PhpReference ref;
        long type;
    }

    /** A placeholder in the SQL text, in order of appearance. */
    static final class Token {
        final boolean named;
        final String name;

        Token(boolean named, String name) {
            this.named = named;
            this.name = name;
        }
    }

    /** One PDOStatement object's SQL, bindings and buffered result. */
    static final class Stmt {
        long dbhId;
        String sql = "";
        String nativeSql = "";
        List<Token> tokens = List.of();
        final Map<String, Integer> namedOrdinals = new HashMap<>();
        boolean mixed;
        boolean repeatedNamed;
        boolean emulated;
        boolean direct;
        final Map<Integer, Param> bindings = new LinkedHashMap<>();
        final Map<Integer, BoundColumn> boundColumns = new LinkedHashMap<>();
        boolean unknownBinding;
        PreparedStatement ps;
        boolean executed;
        List<MysqliFunctions.FieldInfo> cols = List.of();
        List<Object[]> rows = List.of();
        int position;
        long rowCount;
        long fetchMode = -1;
        Object[] fetchArgs = new Object[0];
        String sentSql;
        String errorCode = "00000";
        PhpArray errorInfo;
        boolean hasError;
    }

    static final class IteratorState {
        long stmtId;
        int index = -1;
        boolean hasPending;
        Object pending;
        Object pendingKey;
    }

    private static State state(PhpContext ctx) {
        ContextState cs = ContextState.of(ctx);
        if (cs.pdo == null) {
            cs.pdo = new State();
        }
        return cs.pdo;
    }

    static void disableDriverLogging() {
        if (System.getProperty("mariadb.logging.disable") == null) {
            System.setProperty("mariadb.logging.disable", "true");
        }
    }

    static void register() {
        disableDriverLogging();
        Builtins.def("pdo_drivers", 0, 0, (ctx, a) -> drivers());
    }

    // ------------------------------------------------------------------ classes and objects

    private static PhpClass phpClass(PhpContext ctx, String name) {
        PhpClass cls = ctx.lookupClass(name);
        if (cls == null) {
            throw PhpError.fatal("Class \"" + name + "\" not found");
        }
        return cls;
    }

    private static PhpObject newObject(PhpContext ctx, String className) {
        PhpClass cls = phpClass(ctx, className);
        PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
        cls.populateDefaults(obj);
        return obj;
    }

    private static void set(DynamicObject obj, String key, Object value) {
        DynamicObject.PutNode.getUncached().execute(obj, key, value);
    }

    private static PhpObject statementObject(PhpContext ctx, Stmt st) {
        PhpObject obj = newObject(ctx, "PDOStatement");
        set(obj, "queryString", st.sql);
        state(ctx).statements.put(obj.id(), st);
        return obj;
    }

    private static Stmt stmt(PhpContext ctx, Object value, String fn) {
        if (!(value instanceof PhpObject obj) || !obj.phpClass().name().equalsIgnoreCase("PDOStatement")) {
            throw new PhpError(PhpError.TYPE_ERROR, fn + "(): Argument #1 ($statement) must be of type PDOStatement, "
                    + PhpOps.typeName(value) + " given", null);
        }
        Stmt st = state(ctx).statements.get(obj.id());
        if (st == null) {
            throw new PhpError("PDOStatement object is not initialized");
        }
        return st;
    }

    private static Dbh dbh(PhpContext ctx, Object value, String fn) {
        if (!(value instanceof PhpObject obj) || !obj.phpClass().name().equalsIgnoreCase("PDO")) {
            throw new PhpError(PhpError.TYPE_ERROR, fn + "(): Argument #1 ($pdo) must be of type PDO, "
                    + PhpOps.typeName(value) + " given", null);
        }
        Dbh dbh = state(ctx).connections.get(obj.id());
        if (dbh == null || dbh.conn == null) {
            throw new PhpError("PDO object is not initialized");
        }
        return dbh;
    }

    // ------------------------------------------------------------------ argument helpers

    private static void requireArgs(String fn, Object[] args, int min, int max) {
        if (args.length < min) {
            throw new PhpError(PhpError.ARGUMENT_COUNT,
                    fn + "() expects " + (min == max ? "exactly " + min : "at least " + min)
                            + " argument" + (min == 1 ? "" : "s") + ", " + args.length + " given", null);
        }
        if (max >= 0 && args.length > max) {
            throw new PhpError(PhpError.ARGUMENT_COUNT,
                    fn + "() expects at most " + max + " argument" + (max == 1 ? "" : "s")
                            + ", " + args.length + " given", null);
        }
    }

    private static Object argOr(Object[] args, int i, Object fallback) {
        return i < args.length ? args[i] : fallback;
    }

    /** Argument keys are 1-based positions in the SQL, or parameter names without the colon. */
    private static Object paramKey(Object value, String fn) {
        if (value instanceof Long l) {
            if (l < 1) {
                throw new PhpError(PhpError.VALUE_ERROR,
                        fn + "(): Argument #1 ($param) must be greater than or equal to 1", null);
            }
            return l;
        }
        if (value instanceof Number) {
            return PhpOps.toLong(value);
        }
        String name = PhpOps.toStr(value);
        return name.startsWith(":") ? name.substring(1) : name;
    }

    // ------------------------------------------------------------------ errors

    private static String cleanMessage(SQLException e) {
        return MysqliFunctions.message(e);
    }

    private static boolean connectionLoss(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof IOException || t instanceof java.sql.SQLNonTransientConnectionException) {
                return true;
            }
        }
        return false;
    }

    private static long errno(SQLException e) {
        int code = e.getErrorCode();
        if (code == 0 && connectionLoss(e)) {
            return 2006;
        }
        return code;
    }

    private static String stateOf(SQLException e) {
        return e.getSQLState() == null ? "HY000" : e.getSQLState();
    }

    private static String sqlstateDescription(String sqlstate) {
        return switch (sqlstate) {
            case "01000" -> "Warning";
            case "01004" -> "String data, right truncated";
            case "07002" -> "COUNT field incorrect";
            case "08000" -> "Connection exception";
            case "08001" -> "Client unable to establish connection";
            case "08003" -> "Connection does not exist";
            case "08004" -> "Server rejected the connection";
            case "08006" -> "Connection failure";
            case "08S01" -> "Communication link failure";
            case "21S01" -> "Insert value list does not match column list";
            case "21S02" -> "Degree of derived table does not match column list";
            case "22001" -> "String data, right truncated";
            case "22003" -> "Numeric value out of range";
            case "22007" -> "Invalid datetime format";
            case "22008" -> "Datetime field overflow";
            case "22012" -> "Division by zero";
            case "23000" -> "Integrity constraint violation";
            case "24000" -> "Invalid cursor state";
            case "25000" -> "Invalid transaction state";
            case "28000" -> "Invalid authorization specification";
            case "3D000" -> "Invalid catalog name";
            case "3F000" -> "Invalid schema name";
            case "40001" -> "Serialization failure";
            case "42000" -> "Syntax error or access violation";
            case "42S01" -> "Base table or view already exists";
            case "42S02" -> "Base table or view not found";
            case "42S11" -> "Index already exists";
            case "42S12" -> "Index not found";
            case "42S21" -> "Column already exists";
            case "42S22" -> "Column not found";
            case "HY000" -> "General error";
            case "HY001" -> "Memory allocation error";
            case "HY008" -> "Operation canceled";
            case "HY010" -> "Function sequence error";
            case "HY093" -> "Invalid parameter number";
            case "HY105" -> "Invalid parameter type";
            case "HYC00" -> "Optional feature not implemented";
            case "HYT00" -> "Timeout expired";
            case "IM001" -> "Driver does not support this function";
            default -> "General error";
        };
    }

    /** The exception message PDO builds from the SQLSTATE, native error code and message. */
    private static void noteStatement(Dbh dbh, String sql, boolean hasResult) {
        if (dbh.autocommit) {
            return;
        }
        String keyword = firstKeyword(sql);
        switch (keyword) {
            case "INSERT", "UPDATE", "DELETE", "REPLACE", "LOAD" -> dbh.implicitTransaction = true;
            case "CREATE", "DROP", "ALTER", "TRUNCATE", "RENAME", "GRANT", "REVOKE" ->
                    dbh.implicitTransaction = false;
            default -> {
                // SELECT, SET, SHOW, DO and friends leave the transaction state alone
            }
        }
    }

    private static String firstKeyword(String sql) {
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c) || c == '(') {
                i++;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? n : end + 1;
            } else {
                break;
            }
        }
        int start = i;
        while (i < n && Character.isLetter(sql.charAt(i))) {
            i++;
        }
        return sql.substring(start, i).toUpperCase(Locale.ROOT);
    }

    private static String exceptionMessage(String sqlstate, long errno, String message) {
        String desc = sqlstateDescription(sqlstate);
        if (errno != 0) {
            return "SQLSTATE[" + sqlstate + "]: " + desc + ": " + errno + " " + message;
        }
        if (message.isEmpty()) {
            return "SQLSTATE[" + sqlstate + "]: " + desc;
        }
        return "SQLSTATE[" + sqlstate + "]: " + desc + ": " + message;
    }

    private static PhpArray errorInfoArray(String sqlstate, long errno, String message, boolean withMessage) {
        return errorInfoArray(sqlstate, errno, message, withMessage, true);
    }

    private static PhpArray errorInfoArray(String sqlstate, long errno, String message, boolean withMessage,
                                           boolean withErrno) {
        PhpArray info = new PhpArray();
        info.append(sqlstate);
        if (withErrno) {
            info.append(errno);
        }
        if (withMessage && message != null) {
            info.append(message);
        }
        return info;
    }

    private static PhpException pdoException(PhpContext ctx, String message, Object code, PhpArray errorInfo) {
        com.oracle.truffle.api.nodes.Node site = ctx.currentBuiltinCallSite();
        String file = PhpBacktrace.sourcePath(site);
        int line = PhpBacktrace.sourceLine(site);
        PhpBacktrace.LazyTrace trace = PhpBacktrace.captureFromBuiltin(site, "PDOException");
        PhpObject ex = PhpExceptions.createBuiltinThrowable(ctx, "PDOException", message, 0L, file, line, trace);
        set(ex, "code", code);
        set(ex, "errorInfo", errorInfo == null ? PhpNull.INSTANCE : errorInfo);
        return new PhpException(ex, site);
    }

    /** Records a connection-level error and raises it according to ATTR_ERRMODE. */
    private static boolean failDbh(PhpContext ctx, Dbh dbh, String fn, String sqlstate, long errno, String message) {
        dbh.errorCode = sqlstate;
        dbh.errorErrno = errno;
        dbh.errorMsg = message;
        dbh.hasError = true;
        String raised = exceptionMessage(sqlstate, errno, message);
        switch ((int) dbh.errmode) {
            case (int) ERRMODE_EXCEPTION -> throw pdoException(ctx, raised, sqlstate,
                    errorInfoArray(sqlstate, errno, message, errno != 0,
                            errno != 0 || "HY093".equals(sqlstate)));
            case (int) ERRMODE_WARNING -> ctx.warning(fn + "(): " + raised, 0);
            default -> {
                // silent
            }
        }
        return false;
    }

    private static boolean failStmt(PhpContext ctx, Dbh dbh, Stmt st, String fn, String sqlstate,
                                    long errno, String message) {
        st.errorCode = sqlstate;
        st.hasError = true;
        PhpArray info = errorInfoArray(sqlstate, errno, message, errno != 0, st.emulated || errno != 0);
        st.errorInfo = info;
        String raised = exceptionMessage(sqlstate, errno, message);
        switch ((int) dbh.errmode) {
            case (int) ERRMODE_EXCEPTION -> throw pdoException(ctx, raised, sqlstate, info);
            case (int) ERRMODE_WARNING -> ctx.warning(fn + "(): " + raised, 0);
            default -> {
                // silent
            }
        }
        return false;
    }

    private static boolean failStmtSql(PhpContext ctx, Dbh dbh, Stmt st, String fn, SQLException e) {
        return failStmt(ctx, dbh, st, fn, stateOf(e), errno(e), cleanMessage(e));
    }

    private static boolean failDbhSql(PhpContext ctx, Dbh dbh, String fn, SQLException e) {
        return failDbh(ctx, dbh, fn, stateOf(e), errno(e), cleanMessage(e));
    }

    private static void clearError(Dbh dbh) {
        dbh.errorCode = "00000";
        dbh.errorErrno = 0;
        dbh.errorMsg = null;
        dbh.hasError = false;
    }

    private static void clearStmtError(Stmt st) {
        st.errorCode = "00000";
        st.errorInfo = null;
        st.hasError = false;
    }

    private static PhpArray currentErrorInfo(String code, long errno, String message) {
        if ("00000".equals(code) || message == null) {
            PhpArray ok = new PhpArray();
            ok.append("00000");
            ok.append(PhpNull.INSTANCE);
            ok.append(PhpNull.INSTANCE);
            return ok;
        }
        return errorInfoArray(code, errno, message, errno != 0);
    }

    // ------------------------------------------------------------------ connect

    private record Dsn(String host, int port, String socket, String database, String charset) {
    }

    private static Dsn parseDsn(String dsn) {
        int colon = dsn.indexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("PDO::__construct(): Argument #1 ($dsn) must be a valid data source name");
        }
        String driver = dsn.substring(0, colon);
        if (!driver.equals("mysql")) {
            throw new IllegalArgumentException("could not find driver");
        }
        String host = null;
        int port = 3306;
        String socket = null;
        String database = null;
        String charset = null;
        for (String part : dsn.substring(colon + 1).split(";")) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = part.substring(0, eq);
            String value = part.substring(eq + 1);
            switch (key) {
                case "host" -> host = value;
                case "port" -> {
                    try {
                        int p = Integer.parseInt(value.trim());
                        if (p > 0 && p <= 65535) {
                            port = p;
                        }
                    } catch (NumberFormatException ignored) {
                        // pdo_mysql leaves the default port
                    }
                }
                case "unix_socket" -> socket = value;
                case "dbname" -> database = value;
                case "charset" -> charset = value;
                default -> {
                    // unknown DSN keys are ignored, as pdo_mysql ignores them
                }
            }
        }
        return new Dsn(host, port, socket, database, charset);
    }

    private static PhpObject connectNew(PhpContext ctx, PhpObject self, Object[] args) {
        String dsn;
        try {
            dsn = PhpOps.toStr(argOr(args, 0, ""));
            Dsn parsed = parseDsn(dsn);
            String user = args.length > 1 && args[1] != PhpNull.INSTANCE ? PhpOps.toStr(args[1]) : null;
            String password = args.length > 2 && args[2] != PhpNull.INSTANCE ? PhpOps.toStr(args[2]) : null;
            PhpArray options = args.length > 3 && args[3] instanceof PhpArray arr ? arr : null;
            Dbh dbh = open(ctx, parsed, user, password, options);
            state(ctx).connections.put(self.id(), dbh);
            ctx.request().resources.add(dbh.conn);
            return self;
        } catch (ConnectFailure e) {
            throw pdoException(ctx, e.getMessage(), e.code,
                    errorInfoArray(e.sqlstate, e.nativeCode, e.detail, true));
        } catch (IllegalArgumentException e) {
            throw pdoException(ctx, e.getMessage(), 0L, null);
        }
    }

    private static final class ConnectFailure extends Exception {
        final long code;
        final long nativeCode;
        final String detail;
        final String sqlstate;

        ConnectFailure(long code, long nativeCode, String detail) {
            this("HY000", code, nativeCode, detail);
        }

        ConnectFailure(String sqlstate, long code, long nativeCode, String detail) {
            super("SQLSTATE[" + sqlstate + "] [" + code + "] " + detail);
            this.sqlstate = sqlstate;
            this.code = code;
            this.nativeCode = nativeCode;
            this.detail = detail;
        }
    }

    private static Dbh open(PhpContext ctx, Dsn dsn, String user, String password, PhpArray options)
            throws ConnectFailure {
        Properties props = new Properties();
        if (user != null) {
            props.setProperty("user", PhpStrings.toJava(user));
        }
        if (password != null) {
            props.setProperty("password", PhpStrings.toJava(password));
        }
        props.setProperty("useAffectedRows", "true");
        props.setProperty("tinyInt1isBit", "false");
        props.setProperty("yearIsDateType", "false");
        props.setProperty("transformedBitIsBoolean", "false");
        props.setProperty("connectTimeout", "10000");
        props.setProperty("permitMysqlScheme", "true");
        props.setProperty("jdbcCompliantTruncation", "false");
        props.setProperty("disableSessionTracking", "true");
        props.setProperty("sessionVariables", "sql_mode=DEFAULT");
        props.setProperty("useServerPrepStmts", "true");

        Dbh dbh = new Dbh();
        applyOptions(dbh, options);
        if (dbh.timeout > 0 && dbh.timeout != 28800) {
            props.setProperty("connectTimeout", Long.toString(dbh.timeout * 1000L));
        }
        String initCommand = null;
        if (options != null) {
            for (var entry : options.rawEntries()) {
                if (entry.getKey() instanceof Long key && key == MYSQL_ATTR_INIT_COMMAND
                        && entry.getValue() != PhpNull.INSTANCE) {
                    initCommand = PhpOps.toStr(entry.getValue());
                }
            }
        }

        String host = dsn.host();
        String socket = dsn.socket();
        String javaHost = host == null ? "localhost" : PhpStrings.toJava(host);
        if (socket == null && host == null) {
            socket = DEFAULT_SOCKET;
        }
        if (socket != null) {
            String javaSocket = PhpStrings.toJava(socket);
            if (!Files.exists(Path.of(javaSocket))) {
                throw new ConnectFailure(2002, 2002, "No such file or directory");
            }
            props.setProperty("localSocket", javaSocket);
            javaHost = "localhost";
        }
        if (javaHost.startsWith("[") && javaHost.endsWith("]")) {
            javaHost = javaHost.substring(1, javaHost.length() - 1);
        }
        String database = dsn.database() == null || dsn.database().isEmpty() ? "" : PhpStrings.toJava(dsn.database());
        String url = "jdbc:mariadb://" + (javaHost.contains(":") ? "[" + javaHost + "]" : javaHost) + ":"
                + dsn.port() + "/" + database;
        dbh.url = url;
        dbh.props = props;
        Connection opened = null;
        try {
            opened = MysqliFunctions.blocking(ctx, () -> new org.mariadb.jdbc.Driver().connect(url, props));
            if (opened == null) {
                throw new ConnectFailure(2002, 2002, "Unsupported connection URL");
            }
            Connection conn = opened;
            dbh.conn = conn;
            dbh.serverVersion = serverVersion(conn);
            dbh.hostInfo = (socket != null ? socket : host + " via TCP/IP");
            if (socket != null) {
                dbh.hostInfo = "Localhost via UNIX socket";
            }
            if (dsn.charset() != null) {
                if (dsn.charset().isEmpty()) {
                    throw new ConnectFailure(2019, 2019, "Unknown character set");
                }
                setCharset(ctx, dbh, dsn.charset());
            }
            if (initCommand != null && !initCommand.isEmpty()) {
                try {
                    runPlain(ctx, dbh, initCommand);
                } catch (SQLException e) {
                    throw new ConnectFailure(stateOf(e), errno(e), errno(e), cleanMessage(e));
                }
            }
            applyOptions(dbh, options);
            if (!dbh.autocommit) {
                MysqliFunctions.blocking(ctx, () -> {
                    dbh.conn.setAutoCommit(false);
                    return null;
                });
            }
            return dbh;
        } catch (ConnectFailure e) {
            closeQuietly(opened);
            throw e;
        } catch (SQLException e) {
            closeQuietly(opened);
            throw connectFailure(e, host);
        } catch (RuntimeException e) {
            closeQuietly(opened);
            String message = e.getMessage() == null ? "Connection refused" : e.getMessage();
            throw new ConnectFailure(2002, 2002, message);
        }
    }

    private static void closeQuietly(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            conn.close();
        } catch (SQLException ignored) {
            // the connection is discarded either way
        }
    }

    private static ConnectFailure connectFailure(SQLException e, String host) {
        int code = e.getErrorCode();
        if (code > 0) {
            String state = e.getSQLState();
            if (state != null && state.startsWith("08")) {
                // mysqlnd reports every connect failure as HY000 with a client error code
                return new ConnectFailure(connectCode(e), connectCode(e), cleanMessage(e));
            }
            return new ConnectFailure(code, code, cleanMessage(e));
        }
        String detail = MysqliFunctions.connectFailureMessage(e, host == null ? "localhost" : host);
        long clientCode = 2002;
        if (detail.startsWith("Connection timed out")) {
            clientCode = 2002;
        }
        return new ConnectFailure(clientCode, clientCode, detail);
    }

    private static long connectCode(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException) {
                return 2002;
            }
            if (t instanceof UnknownHostException) {
                return 2002;
            }
            if (t instanceof java.net.SocketTimeoutException) {
                return 2002;
            }
        }
        return e.getErrorCode() == 0 ? 2002 : e.getErrorCode();
    }

    private static String serverVersion(Connection conn) {
        try {
            String v = conn instanceof org.mariadb.jdbc.Connection mariadb
                    ? mariadb.getContext().getVersion().getVersion()
                    : conn.getMetaData().getDatabaseProductVersion();
            return PhpStrings.fromJava(v);
        } catch (SQLException | RuntimeException e) {
            return "";
        }
    }

    private static void setCharset(PhpContext ctx, Dbh dbh, String charset) throws SQLException, ConnectFailure {
        try {
            runPlain(ctx, dbh, "SET NAMES " + charset);
        } catch (SQLException e) {
            if (e.getErrorCode() == 1115) {
                throw new ConnectFailure(2019, 2019, "Unknown character set");
            }
            throw e;
        }
    }

    private static long runPlain(PhpContext ctx, Dbh dbh, String sql) throws SQLException {
        return MysqliFunctions.blocking(ctx, () -> {
            try (Statement st = dbh.conn.createStatement()) {
                boolean result = st.execute(MysqliSql.toJava(sql));
                if (result) {
                    try (ResultSet rs = st.getResultSet()) {
                        while (rs.next()) {
                            // discard init-command results
                        }
                    }
                    return 0L;
                }
                return Math.max(0, st.getLargeUpdateCount());
            }
        });
    }

    // ------------------------------------------------------------------ options and attributes

    private static void applyOptions(Dbh dbh, PhpArray options) {
        if (options == null) {
            return;
        }
        for (var entry : options.rawEntries()) {
            long attr = entry.getKey() instanceof Long l ? l : PhpOps.toLong(entry.getKey());
            Object value = entry.getValue();
            switch ((int) attr) {
                case (int) ATTR_ERRMODE -> dbh.errmode = validErrmode(value);
                case (int) ATTR_DEFAULT_FETCH_MODE -> dbh.defaultFetch = PhpOps.toLong(value);
                case (int) ATTR_CASE -> dbh.attrCase = validCase(value);
                case (int) ATTR_ORACLE_NULLS -> dbh.oracleNulls = PhpOps.toLong(value);
                case (int) ATTR_STRINGIFY_FETCHES -> dbh.stringify = PhpOps.toBool(value);
                case (int) ATTR_EMULATE_PREPARES -> dbh.emulate = PhpOps.toBool(value);
                case (int) ATTR_PERSISTENT -> dbh.persistent = PhpOps.toBool(value);
                case (int) ATTR_TIMEOUT -> dbh.timeout = PhpOps.toLong(value);
                case (int) ATTR_AUTOCOMMIT -> dbh.autocommit = PhpOps.toBool(value);
                case (int) ATTR_DEFAULT_STR_PARAM -> dbh.defaultStrParam = PhpOps.toLong(value);
                case (int) ATTR_STATEMENT_CLASS -> {
                    if (value instanceof PhpArray arr && arr.size() > 0 && arr.get(0) instanceof String name) {
                        dbh.statementClass = name;
                        dbh.statementClassArgs.clear();
                        if (arr.size() > 1 && arr.get(1) instanceof PhpArray ctor) {
                            for (var e : ctor.rawEntries()) {
                                dbh.statementClassArgs.add(e.getValue());
                            }
                        }
                    }
                }
                case (int) MYSQL_ATTR_USE_BUFFERED_QUERY -> dbh.buffered = PhpOps.toBool(value);
                case (int) ATTR_FETCH_TABLE_NAMES -> {
                    // accepted, exactly as pdo_mysql accepts it
                }
                default -> {
                    // ignored
                }
            }
        }
    }

    private static long validErrmode(Object value) {
        long mode = PhpOps.toLong(value);
        if (mode < ERRMODE_SILENT || mode > ERRMODE_EXCEPTION) {
            throw new PhpError(PhpError.VALUE_ERROR,
                    "PDO::setAttribute(): Argument #2 ($value) Error mode must be one of the PDO::ERRMODE_* constants",
                    null);
        }
        return mode;
    }

    private static long validCase(Object value) {
        long mode = PhpOps.toLong(value);
        if (mode < CASE_NATURAL || mode > CASE_LOWER) {
            throw new PhpError(PhpError.VALUE_ERROR,
                    "PDO::setAttribute(): Argument #2 ($value) Case folding mode must be one of the PDO::CASE_* constants",
                    null);
        }
        return mode;
    }

    // ------------------------------------------------------------------ PDO methods

    static Object construct(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDO::__construct", args, 1, 4);
        connectNew(ctx, self, args);
        return PhpNull.INSTANCE;
    }

    static Object connectStatic(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDO::connect", args, 1, 4);
        PhpObject pdo = newObject(ctx, "PDO");
        connectNew(ctx, pdo, args);
        return pdo;
    }

    static Object getAvailableDrivers(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDO::getAvailableDrivers", args, 0, 0);
        return drivers();
    }

    private static PhpArray drivers() {
        PhpArray drivers = new PhpArray();
        drivers.append("mysql");
        return drivers;
    }

    static Object beginTransaction(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDO::beginTransaction", args, 0, 0);
        Dbh dbh = dbh(ctx, self, "PDO::beginTransaction");
        clearError(dbh);
        if (dbh.explicitTransaction || (!dbh.autocommit && dbh.implicitTransaction)) {
            throw pdoException(ctx, "There is already an active transaction", 0L, null);
        }
        try {
            MysqliFunctions.blocking(ctx, () -> {
                dbh.conn.setAutoCommit(false);
                return null;
            });
            dbh.explicitTransaction = true;
            dbh.implicitTransaction = false;
            return true;
        } catch (SQLException e) {
            return failDbhSql(ctx, dbh, "PDO::beginTransaction", e);
        }
    }

    static Object commit(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDO::commit", args, 0, 0);
        Dbh dbh = dbh(ctx, self, "PDO::commit");
        clearError(dbh);
        if (!dbh.explicitTransaction && !(!dbh.autocommit && dbh.implicitTransaction)) {
            throw pdoException(ctx, "There is no active transaction", 0L, null);
        }
        try {
            MysqliFunctions.blocking(ctx, () -> {
                dbh.conn.commit();
                if (dbh.explicitTransaction) {
                    dbh.conn.setAutoCommit(true);
                }
                return null;
            });
            dbh.explicitTransaction = false;
            dbh.implicitTransaction = false;
            return true;
        } catch (SQLException e) {
            return failDbhSql(ctx, dbh, "PDO::commit", e);
        }
    }

    static Object rollBack(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDO::rollBack", args, 0, 0);
        Dbh dbh = dbh(ctx, self, "PDO::rollBack");
        clearError(dbh);
        if (!dbh.explicitTransaction && !(!dbh.autocommit && dbh.implicitTransaction)) {
            throw pdoException(ctx, "There is no active transaction", 0L, null);
        }
        try {
            MysqliFunctions.blocking(ctx, () -> {
                dbh.conn.rollback();
                if (dbh.explicitTransaction) {
                    dbh.conn.setAutoCommit(true);
                }
                return null;
            });
            dbh.explicitTransaction = false;
            dbh.implicitTransaction = false;
            return true;
        } catch (SQLException e) {
            return failDbhSql(ctx, dbh, "PDO::rollBack", e);
        }
    }

    static Object inTransaction(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDO::inTransaction", args, 0, 0);
        Dbh dbh = dbh(ctx, self, "PDO::inTransaction");
        return dbh.explicitTransaction || (!dbh.autocommit && dbh.implicitTransaction);
    }

    static Object errorCode(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDO::errorCode", args, 0, 0);
        return dbh(ctx, self, "PDO::errorCode").errorCode;
    }

    static Object errorInfo(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDO::errorInfo", args, 0, 0);
        Dbh dbh = dbh(ctx, self, "PDO::errorInfo");
        return currentErrorInfo(dbh.errorCode, dbh.errorErrno, dbh.errorMsg);
    }

    static Object lastInsertId(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDO::lastInsertId", args, 0, 1);
        Dbh dbh = dbh(ctx, self, "PDO::lastInsertId");
        clearError(dbh);
        return dbh.lastInsertId;
    }

    static Object getAttribute(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDO::getAttribute", args, 1, 1);
        Dbh dbh = dbh(ctx, self, "PDO::getAttribute");
        long attr = PhpOps.toLong(args[0]);
        switch ((int) attr) {
            case (int) ATTR_AUTOCOMMIT:
                return dbh.autocommit;
            case (int) ATTR_ERRMODE:
                return dbh.errmode;
            case (int) ATTR_CASE:
                return dbh.attrCase;
            case (int) ATTR_ORACLE_NULLS:
                return dbh.oracleNulls;
            case (int) ATTR_PERSISTENT:
                return dbh.persistent;
            case (int) ATTR_DRIVER_NAME:
                return "mysql";
            case (int) ATTR_STRINGIFY_FETCHES:
                return dbh.stringify;
            case (int) ATTR_EMULATE_PREPARES:
                return dbh.emulate;
            case (int) ATTR_DEFAULT_FETCH_MODE:
                return dbh.defaultFetch;
            case (int) ATTR_DEFAULT_STR_PARAM:
                return dbh.defaultStrParam;
            case (int) MYSQL_ATTR_USE_BUFFERED_QUERY:
                return dbh.buffered;
            case (int) ATTR_SERVER_VERSION:
                return dbh.serverVersion;
            case (int) ATTR_CLIENT_VERSION:
                return CLIENT_VERSION;
            case (int) ATTR_SERVER_INFO:
                return serverInfo(ctx, dbh);
            case (int) ATTR_CONNECTION_STATUS:
                return dbh.hostInfo;
            case (int) ATTR_FETCH_TABLE_NAMES:
                return false;
            case (int) ATTR_STATEMENT_CLASS: {
                PhpArray cls = new PhpArray();
                cls.append(dbh.statementClass);
                return cls;
            }
            default:
                return unsupportedGet(ctx, dbh.errmode, "PDO::getAttribute",
                        "driver does not support that attribute");
        }
    }

    private static Object unsupportedGet(PhpContext ctx, long errmode, String fn, String detail) {
        String message = exceptionMessage("IM001", 0, detail);
        if (errmode == ERRMODE_EXCEPTION) {
            throw pdoException(ctx, message, "IM001", errorInfoArray("IM001", 0, detail, false));
        }
        ctx.warning(fn + "(): " + message, 0);
        return false;
    }

    static Object setAttribute(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDO::setAttribute", args, 2, 2);
        Dbh dbh = dbh(ctx, self, "PDO::setAttribute");
        clearError(dbh);
        long attr = PhpOps.toLong(args[0]);
        Object value = args[1];
        switch ((int) attr) {
            case (int) ATTR_ERRMODE -> dbh.errmode = validErrmode(value);
            case (int) ATTR_DEFAULT_FETCH_MODE -> dbh.defaultFetch = PhpOps.toLong(value);
            case (int) ATTR_CASE -> dbh.attrCase = validCase(value);
            case (int) ATTR_ORACLE_NULLS -> dbh.oracleNulls = PhpOps.toLong(value);
            case (int) ATTR_STRINGIFY_FETCHES -> dbh.stringify = PhpOps.toBool(value);
            case (int) ATTR_EMULATE_PREPARES -> dbh.emulate = PhpOps.toBool(value);
            case (int) ATTR_DEFAULT_STR_PARAM -> dbh.defaultStrParam = PhpOps.toLong(value);
            case (int) MYSQL_ATTR_USE_BUFFERED_QUERY -> dbh.buffered = PhpOps.toBool(value);
            case (int) ATTR_FETCH_TABLE_NAMES -> {
                // accepted and stored nowhere, like pdo_mysql
            }
            case (int) ATTR_STATEMENT_CLASS -> {
                if (!(value instanceof PhpArray arr) || arr.size() == 0 || !(arr.get(0) instanceof String)) {
                    throw new PhpError(PhpError.TYPE_ERROR,
                            "PDO::setAttribute(): Argument #2 ($value) PDO::ATTR_STATEMENT_CLASS value must be of type array, "
                                    + PhpOps.typeName(value) + " given", null);
                }
                dbh.statementClass = (String) arr.get(0);
                dbh.statementClassArgs.clear();
                if (arr.size() > 1 && arr.get(1) instanceof PhpArray ctor) {
                    for (var e : ctor.rawEntries()) {
                        dbh.statementClassArgs.add(e.getValue());
                    }
                }
            }
            case (int) ATTR_AUTOCOMMIT -> {
                boolean on = PhpOps.toBool(value);
                try {
                    MysqliFunctions.blocking(ctx, () -> {
                        dbh.conn.setAutoCommit(on);
                        return null;
                    });
                    dbh.autocommit = on;
                    dbh.implicitTransaction = false;
                    if (on) {
                        dbh.explicitTransaction = false;
                    }
                } catch (SQLException e) {
                    return failDbhSql(ctx, dbh, "PDO::setAttribute", e);
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private static String serverInfo(PhpContext ctx, Dbh dbh) {
        try {
            Map<String, Long> status = MysqliFunctions.blocking(ctx, () -> {
                Map<String, Long> values = new HashMap<>();
                try (Statement st = dbh.conn.createStatement();
                     ResultSet rs = st.executeQuery("SHOW GLOBAL STATUS WHERE Variable_name IN ('Uptime',"
                             + "'Threads_connected','Questions','Slow_queries','Opened_tables','Open_tables')")) {
                    while (rs.next()) {
                        try {
                            values.put(rs.getString(1), Long.parseLong(rs.getString(2)));
                        } catch (NumberFormatException ignored) {
                            // not a counter
                        }
                    }
                }
                return values;
            });
            long uptime = status.getOrDefault("Uptime", 0L);
            long questions = status.getOrDefault("Questions", 0L);
            double avg = uptime > 0 ? (double) (questions - 1) / uptime : 0.0;
            return "Uptime: " + uptime
                    + "  Threads: " + status.getOrDefault("Threads_connected", 0L)
                    + "  Questions: " + questions
                    + "  Slow queries: " + status.getOrDefault("Slow_queries", 0L)
                    + "  Opens: " + status.getOrDefault("Opened_tables", 0L)
                    + "  Open tables: " + status.getOrDefault("Open_tables", 0L)
                    + "  Queries per second avg: " + String.format(Locale.ROOT, "%.3f", avg);
        } catch (SQLException | RuntimeException e) {
            return "";
        }
    }

    static Object quote(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDO::quote", args, 1, 2);
        Dbh dbh = dbh(ctx, self, "PDO::quote");
        clearError(dbh);
        String value = args[0] == PhpNull.INSTANCE ? "" : PhpOps.toStr(args[0]);
        return "'" + MysqliFunctions.escape(value) + "'";
    }

    static Object exec(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDO::exec", args, 1, 1);
        Dbh dbh = dbh(ctx, self, "PDO::exec");
        clearError(dbh);
        String sql = PhpOps.toStr(args[0]);
        if (sql.isEmpty()) {
            throw new PhpError(PhpError.VALUE_ERROR,
                    "PDO::exec(): Argument #1 ($statement) must not be empty", null);
        }
        try {
            Run run = runDirect(ctx, dbh, sql);
            dbh.lastInsertId = Long.toString(run.insertId);
            noteStatement(dbh, sql, run.hasResult);
            return run.hasResult ? 0L : run.affected;
        } catch (SQLException e) {
            failDbhSql(ctx, dbh, "PDO::exec", e);
            return false;
        }
    }

    static Object query(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDO::query", args, 1, 256);
        Dbh dbh = dbh(ctx, self, "PDO::query");
        clearError(dbh);
        String sql = PhpOps.toStr(args[0]);
        if (sql.isEmpty()) {
            throw new PhpError(PhpError.VALUE_ERROR,
                    "PDO::query(): Argument #1 ($query) must not be empty", null);
        }
        Stmt st = new Stmt();
        st.dbhId = self.id();
        st.sql = sql;
        st.nativeSql = sql;
        st.direct = true;
        st.emulated = false;
        try {
            runDirectInto(ctx, dbh, st, sql);
        } catch (SQLException e) {
            failDbhSql(ctx, dbh, "PDO::query", e);
            return false;
        }
        noteStatement(dbh, sql, !st.cols.isEmpty());
        if (args.length > 1 && args[1] != PhpNull.INSTANCE) {
            long mode = PhpOps.toLong(args[1]);
            Object[] modeArgs = new Object[Math.max(0, args.length - 2)];
            System.arraycopy(args, 2, modeArgs, 0, modeArgs.length);
            st.fetchMode = mode;
            st.fetchArgs = modeArgs;
        }
        PhpObject stmtObj = statementObject(ctx, st);
        if (!"PDOStatement".equalsIgnoreCase(dbh.statementClass)) {
            stmtObj = retypeStatement(ctx, dbh, stmtObj, st);
        }
        return stmtObj;
    }

    private static PhpObject retypeStatement(PhpContext ctx, Dbh dbh, PhpObject statement, Stmt st) {
        PhpClass cls = ctx.lookupOrAutoloadClass(dbh.statementClass);
        if (cls == null) {
            throw pdoException(ctx, "Class \"" + dbh.statementClass + "\" not found", 0L, null);
        }
        PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
        cls.populateDefaults(obj);
        set(obj, "queryString", st.sql);
        state(ctx).statements.put(obj.id(), st);
        state(ctx).statements.remove(statement.id());
        return obj;
    }

    static Object prepare(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDO::prepare", args, 1, 2);
        Dbh dbh = dbh(ctx, self, "PDO::prepare");
        clearError(dbh);
        String sql = PhpOps.toStr(args[0]);
        if (sql.isEmpty()) {
            throw new PhpError(PhpError.VALUE_ERROR,
                    "PDO::prepare(): Argument #1 ($query) must not be empty", null);
        }
        Stmt st = new Stmt();
        st.dbhId = self.id();
        st.sql = sql;
        st.emulated = dbh.emulate;
        parseTokens(st);
        if (args.length > 1 && args[1] instanceof PhpArray options) {
            applyStatementOptions(ctx, dbh, st, options);
        }
        if (!st.emulated) {
            if (st.mixed) {
                failDbh(ctx, dbh, "PDO::prepare", "HY093", 0, "mixed named and positional parameters");
                return false;
            }
            try {
                st.ps = MysqliFunctions.blocking(ctx, () -> dbh.conn.prepareStatement(st.nativeSql,
                        Statement.RETURN_GENERATED_KEYS));
                // The driver defers COM_STMT_PREPARE until first use; pdo_mysql prepares at
                // prepare() time, so ask for the metadata now to surface the same errors here.
                MysqliFunctions.blocking(ctx, () -> {
                    st.ps.getMetaData();
                    return null;
                });
            } catch (SQLException e) {
                closeQuietly(st.ps);
                st.ps = null;
                failDbhSql(ctx, dbh, "PDO::prepare", e);
                return false;
            }
        }
        PhpObject stmtObj = statementObject(ctx, st);
        if (!"PDOStatement".equalsIgnoreCase(dbh.statementClass)) {
            stmtObj = retypeStatement(ctx, dbh, stmtObj, st);
        }
        return stmtObj;
    }

    private static void applyStatementOptions(PhpContext ctx, Dbh dbh, Stmt st, PhpArray options) {
        for (var entry : options.rawEntries()) {
            long attr = entry.getKey() instanceof Long l ? l : PhpOps.toLong(entry.getKey());
            Object value = entry.getValue();
            if (attr == ATTR_CURSOR) {
                long cursor = PhpOps.toLong(value);
                if (cursor != 0) {
                    // pdo_mysql supports forward-only; CURSOR_SCROLL falls back to the same
                }
            } else if (attr == ATTR_STATEMENT_CLASS) {
                if (value instanceof PhpArray arr && arr.size() > 0 && arr.get(0) instanceof String name) {
                    dbh.statementClass = name;
                    dbh.statementClassArgs.clear();
                    if (arr.size() > 1 && arr.get(1) instanceof PhpArray ctor) {
                        for (var e : ctor.rawEntries()) {
                            dbh.statementClassArgs.add(e.getValue());
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ SQL tokenizer

    /** Finds {@code ?} and {@code :name} placeholders outside strings, quoted names and comments. */
    private static void parseTokens(Stmt st) {
        String sql = st.sql;
        List<Token> tokens = new ArrayList<>();
        Map<String, Integer> named = new HashMap<>();
        StringBuilder nativeSql = new StringBuilder(sql.length());
        boolean sawNamed = false;
        boolean sawPositional = false;
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int end = skipQuoted(sql, i, c);
                nativeSql.append(sql, i, end);
                i = end;
                continue;
            }
            if (c == '#') {
                int end = sql.indexOf('\n', i);
                if (end < 0) {
                    end = n;
                }
                nativeSql.append(sql, i, end);
                i = end;
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-'
                    && (i + 2 >= n || Character.isWhitespace(sql.charAt(i + 2)))) {
                int end = sql.indexOf('\n', i);
                if (end < 0) {
                    end = n;
                }
                nativeSql.append(sql, i, end);
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                nativeSql.append(sql, i, end);
                i = end;
                continue;
            }
            if (c == '?') {
                sawPositional = true;
                tokens.add(new Token(false, null));
                nativeSql.append('?');
                i++;
                continue;
            }
            if (c == ':' && i + 1 < n && (Character.isLetter(sql.charAt(i + 1)) || sql.charAt(i + 1) == '_')) {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
                    j++;
                }
                String name = sql.substring(i + 1, j);
                sawNamed = true;
                if (named.containsKey(name)) {
                    st.repeatedNamed = true;
                    tokens.add(new Token(true, name));
                } else {
                    named.put(name, tokens.size());
                    tokens.add(new Token(true, name));
                }
                nativeSql.append('?');
                i = j;
                continue;
            }
            nativeSql.append(c);
            i++;
        }
        st.tokens = List.copyOf(tokens);
        st.namedOrdinals.putAll(named);
        st.nativeSql = nativeSql.toString();
        st.mixed = sawNamed && sawPositional;
    }

    private static int skipQuoted(String sql, int start, char quote) {
        int i = start + 1;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\\' && quote != '`') {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < n && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return n;
    }

    private static Integer ordinalFor(Stmt st, Object key) {
        if (key instanceof Long l) {
            int index = (int) (l - 1);
            if (index >= 0 && index < st.tokens.size() && !st.tokens.get(index).named) {
                return index;
            }
            return null;
        }
        String name = PhpOps.toStr(key);
        if (name.startsWith(":")) {
            name = name.substring(1);
        }
        return st.namedOrdinals.get(name);
    }

    /** execute()'s parameter array is 0-based for positional placeholders. */
    private static Integer ordinalForExecute(Stmt st, Object key) {
        if (key instanceof Long l) {
            int index = (int) (long) l;
            if (index >= 0 && index < st.tokens.size() && !st.tokens.get(index).named) {
                return index;
            }
            return null;
        }
        String name = PhpOps.toStr(key);
        if (name.startsWith(":")) {
            name = name.substring(1);
        }
        return st.namedOrdinals.get(name);
    }

    private static void bind(Stmt st, long type, Object key, Object value, PhpReference ref, String fn) {
        Integer found = ordinalFor(st, key);
        if (found == null) {
            st.unknownBinding = true;
            return;
        }
        st.bindings.put(found, new Param(type, ref == null ? PhpOps.copyOnAssign(value) : PhpNull.INSTANCE, ref));
    }

    // ------------------------------------------------------------------ statement methods

    static Object bindValue(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDOStatement::bindValue", args, 2, 3);
        Stmt st = stmt(ctx, self, "PDOStatement::bindValue");
        Object key = paramKey(args[0], "PDOStatement::bindValue");
        long type = args.length > 2 ? PhpOps.toLong(args[2]) : PARAM_STR;
        bind(st, type, key, args[1], null, "PDOStatement::bindValue");
        return true;
    }

    static Object bindParam(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDOStatement::bindParam", args, 2, 5);
        Stmt st = stmt(ctx, self, "PDOStatement::bindParam");
        Object key = paramKey(args[0], "PDOStatement::bindParam");
        long type = args.length > 2 ? PhpOps.toLong(args[2]) : PARAM_STR;
        PhpReference ref = args[1] instanceof PhpReference r ? r : null;
        bind(st, type, key, args[1], ref, "PDOStatement::bindParam");
        return true;
    }

    static Object bindColumn(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDOStatement::bindColumn", args, 2, 5);
        Stmt st = stmt(ctx, self, "PDOStatement::bindColumn");
        Object key = args[0];
        BoundColumn bc = new BoundColumn();
        if (key instanceof Long l) {
            bc.index = (int) (l - 1);
        } else {
            bc.name = PhpOps.toStr(key);
            bc.index = -1;
        }
        bc.type = args.length > 2 ? PhpOps.toLong(args[2]) : PARAM_STR;
        bc.ref = args[1] instanceof PhpReference r ? r : null;
        st.boundColumns.put(st.boundColumns.size(), bc);
        return true;
    }

    static Object execute(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDOStatement::execute", args, 0, 1);
        Stmt st = stmt(ctx, self, "PDOStatement::execute");
        Dbh dbh = state(ctx).connections.get(st.dbhId);
        if (dbh == null || dbh.conn == null) {
            throw new PhpError("PDO object is not initialized");
        }
        clearStmtError(st);
        PhpArray params = args.length > 0 && args[0] instanceof PhpArray arr ? arr : null;
        if (st.mixed) {
            failStmt(ctx, dbh, st, "PDOStatement::execute", "HY093", 0, "mixed named and positional parameters");
            return false;
        }
        if (st.repeatedNamed && !st.emulated) {
            failStmt(ctx, dbh, st, "PDOStatement::execute", "HY093", 0, "");
            return false;
        }
        Map<Integer, Param> effective = new LinkedHashMap<>();
        boolean unknown = st.unknownBinding;
        boolean tooMany = false;
        if (params != null) {
            unknown = false;
            for (var entry : params.rawEntries()) {
                Object key = entry.getKey();
                Integer ordinal = ordinalForExecute(st, key);
                if (ordinal == null) {
                    if (key instanceof Long) {
                        tooMany = true;
                    } else {
                        unknown = true;
                    }
                    continue;
                }
                Object value = entry.getValue();
                effective.put(ordinal, new Param(PARAM_STR,
                        value instanceof PhpReference ref ? ref.getValue() : value, null));
            }
        } else {
            effective.putAll(st.bindings);
        }
        if (params != null) {
            // pdo keeps the values supplied to execute() bound for later calls
            st.bindings.clear();
            st.bindings.putAll(effective);
        }
        if (unknown) {
            String detail = st.emulated ? "parameter was not defined" : "";
            failStmt(ctx, dbh, st, "PDOStatement::execute", "HY093", 0, detail);
            return false;
        }
        boolean mismatch = false;
        for (int i = 0; i < st.tokens.size(); i++) {
            if (paramFor(st, effective, i) == null) {
                mismatch = true;
                break;
            }
        }
        boolean emptyParams = params != null && params.size() == 0;
        if (!st.emulated) {
            if (mismatch || tooMany) {
                failStmt(ctx, dbh, st, "PDOStatement::execute", "HY093", 0, "");
                return false;
            }
        } else if (!emptyParams) {
            if (params != null) {
                if (params.size() != parameterCount(st)) {
                    failStmt(ctx, dbh, st, "PDOStatement::execute", "HY093", 0,
                            "number of bound variables does not match number of tokens");
                    return false;
                }
                if (tooMany) {
                    failStmt(ctx, dbh, st, "PDOStatement::execute", "HY093", 0, "parameter was not defined");
                    return false;
                }
            } else {
                if (st.unknownBinding) {
                    failStmt(ctx, dbh, st, "PDOStatement::execute", "HY093", 0, "parameter was not defined");
                    return false;
                }
                if (mismatch) {
                    failStmt(ctx, dbh, st, "PDOStatement::execute", "HY093", 0,
                            "number of bound variables does not match number of tokens");
                    return false;
                }
            }
            if (mismatch) {
                failStmt(ctx, dbh, st, "PDOStatement::execute", "HY093", 0,
                        "number of bound variables does not match number of tokens");
                return false;
            }
        }
        String sql;
        if (st.emulated) {
            sql = substitute(st, effective);
            st.sentSql = sql;
        } else {
            sql = st.nativeSql;
        }
        try {
            Run run = st.emulated || st.direct
                    ? runDirect(ctx, dbh, sql)
                    : runPrepared(ctx, dbh, st, effective);
            dbh.lastInsertId = Long.toString(run.insertId);
            noteStatement(dbh, st.sql, run.hasResult);
            st.executed = true;
            st.position = 0;
            st.cols = run.cols;
            st.rows = run.rows;
            st.rowCount = run.hasResult ? run.rows.size() : run.affected;
            return true;
        } catch (SQLException e) {
            failStmtSql(ctx, dbh, st, "PDOStatement::execute", e);
            return false;
        }
    }

    /** Tokens that need a value: each positional one, each distinct named one. */
    private static int parameterCount(Stmt st) {
        int count = 0;
        Map<String, Boolean> names = new HashMap<>();
        for (Token token : st.tokens) {
            if (token.named) {
                if (names.put(token.name, Boolean.TRUE) == null) {
                    count++;
                }
            } else {
                count++;
            }
        }
        return count;
    }

    private static Param paramFor(Stmt st, Map<Integer, Param> params, int ordinal) {        Param p = params.get(ordinal);
        if (p == null && ordinal < st.tokens.size()) {
            Token token = st.tokens.get(ordinal);
            if (token.named) {
                Integer first = st.namedOrdinals.get(token.name);
                if (first != null) {
                    p = params.get(first);
                }
            }
        }
        return p;
    }

    private static String substitute(Stmt st, Map<Integer, Param> params) {
        String sql = st.sql;
        StringBuilder out = new StringBuilder(sql.length() + 32);
        int n = sql.length();
        int i = 0;
        int token = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int end = skipQuoted(sql, i, c);
                out.append(sql, i, end);
                i = end;
                continue;
            }
            if (c == '#') {
                int end = sql.indexOf('\n', i);
                if (end < 0) {
                    end = n;
                }
                out.append(sql, i, end);
                i = end;
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-'
                    && (i + 2 >= n || Character.isWhitespace(sql.charAt(i + 2)))) {
                int end = sql.indexOf('\n', i);
                if (end < 0) {
                    end = n;
                }
                out.append(sql, i, end);
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                out.append(sql, i, end);
                i = end;
                continue;
            }
            if (c == '?') {
                Param p = paramFor(st, params, token++);
                out.append(p == null ? "?" : literal(p));
                i++;
                continue;
            }
            if (c == ':' && i + 1 < n && (Character.isLetter(sql.charAt(i + 1)) || sql.charAt(i + 1) == '_')) {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
                    j++;
                }
                Param p = paramFor(st, params, token++);
                out.append(p == null ? sql.substring(i, j) : literal(p));
                i = j;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String literal(Param p) {
        Object value = p.read();
        if (p.type == PARAM_NULL || value == null || value == PhpNull.INSTANCE) {
            return "NULL";
        }
        return switch ((int) p.type) {
            case (int) PARAM_INT -> Long.toString(PhpOps.toLong(value));
            case (int) PARAM_BOOL -> PhpOps.toBool(value) ? "1" : "0";
            case (int) PARAM_LOB -> "_binary'" + MysqliFunctions.escape(PhpOps.toStr(value)) + "'";
            default -> "'" + MysqliFunctions.escape(PhpOps.toStr(value)) + "'";
        };
    }

    private record Run(List<MysqliFunctions.FieldInfo> cols, List<Object[]> rows, long affected,
                       long insertId, boolean hasResult) {
    }

    private static Run runDirect(PhpContext ctx, Dbh dbh, String sql) throws SQLException {        return MysqliFunctions.blocking(ctx, () -> {
            try (Statement st = dbh.conn.createStatement()) {
                boolean has = st.execute(MysqliSql.toJava(sql), Statement.RETURN_GENERATED_KEYS);
                if (has) {
                    try (ResultSet rs = st.getResultSet()) {
                        Read read = read(rs);
                        return new Run(read.cols, read.rows, 0, 0, true);
                    }
                }
                long count = st.getLargeUpdateCount();
                long id = generatedId(st);
                return new Run(List.of(), List.of(), count, id, false);
            }
        });
    }

    private static void runDirectInto(PhpContext ctx, Dbh dbh, Stmt st, String sql) throws SQLException {
        Run run = runDirect(ctx, dbh, sql);
        dbh.lastInsertId = Long.toString(run.insertId);
        st.executed = true;
        st.position = 0;
        st.cols = run.cols;
        st.rows = run.rows;
        st.rowCount = run.hasResult ? run.rows.size() : run.affected;
    }

    private static Run runPrepared(PhpContext ctx, Dbh dbh, Stmt st, Map<Integer, Param> params) throws SQLException {
        return MysqliFunctions.blocking(ctx, () -> {
            PreparedStatement ps = st.ps;
            for (int i = 0; i < st.tokens.size(); i++) {
                bindJdbc(ps, i + 1, paramFor(st, params, i));
            }
            boolean has = ps.execute();
            if (has) {
                try (ResultSet rs = ps.getResultSet()) {
                    Read read = read(rs);
                    return new Run(read.cols, read.rows, 0, 0, true);
                }
            }
            long count = ps.getLargeUpdateCount();
            long id = generatedId(ps);
            return new Run(List.of(), List.of(), count, id, false);
        });
    }

    private static void closeQuietly(PreparedStatement ps) {
        if (ps == null) {
            return;
        }
        try {
            ps.close();
        } catch (SQLException ignored) {
            // the statement is discarded either way
        }
    }

    private static long generatedId(Statement st) {
        try (ResultSet keys = st.getGeneratedKeys()) {
            if (keys != null && keys.next()) {
                return keys.getLong(1);
            }
        } catch (SQLException | RuntimeException ignored) {
            // no generated keys for this statement
        }
        return 0;
    }

    private static void bindJdbc(PreparedStatement ps, int index, Param p) throws SQLException {
        Object value = p == null ? null : p.read();
        if (p == null || p.type == PARAM_NULL || value == null || value == PhpNull.INSTANCE) {
            ps.setNull(index, Types.NULL);
            return;
        }
        if (p.type == PARAM_STR) {
            ps.setString(index, PhpOps.toStr(value));
            return;
        }
        if (value instanceof Long l) {
            ps.setLong(index, l);
        } else if (value instanceof Double d) {
            ps.setDouble(index, d);
        } else if (value instanceof Boolean b) {
            ps.setLong(index, b ? 1 : 0);
        } else {
            ps.setString(index, PhpOps.toStr(value));
        }
    }

    private record Read(List<MysqliFunctions.FieldInfo> cols, List<Object[]> rows) {
    }

    private static Read read(ResultSet rs) throws SQLException {
        Object[] driverCols = MysqliFunctions.driverColumns(rs);
        ResultSetMetaData md = null;
        int n;
        if (driverCols != null) {
            n = driverCols.length;
        } else {
            md = rs.getMetaData();
            n = md.getColumnCount();
        }
        List<MysqliFunctions.FieldInfo> cols = new ArrayList<>(n);
        int[] types = new int[n];
        for (int i = 0; i < n; i++) {
            MysqliFunctions.FieldInfo f;
            if (driverCols != null && driverCols[i] instanceof org.mariadb.jdbc.client.Column c) {
                f = MysqliFunctions.field(c);
            } else {
                if (md == null) {
                    md = rs.getMetaData();
                }
                f = MysqliFunctions.field(md, i + 1);
            }
            cols.add(f);
            types[i] = (int) f.type();
        }
        List<Object[]> rows = new ArrayList<>();
        while (rs.next()) {
            Object[] row = new Object[n];
            for (int i = 0; i < n; i++) {
                row[i] = readValue(rs, i + 1, types[i]);
            }
            rows.add(row);
        }
        return new Read(List.copyOf(cols), rows);
    }

    private static Object readValue(ResultSet rs, int index, int type) throws SQLException {
        switch (type) {
            case 16 -> {
                // BIT: mysqlnd returns the unsigned integer, or a decimal string when it overflows
                byte[] bytes = rs.getBytes(index);
                if (bytes == null) {
                    return PhpNull.INSTANCE;
                }
                BigInteger value = new BigInteger(1, bytes);
                return value.bitLength() < 64 ? (Object) value.longValue() : value.toString();
            }
            case 1, 2, 3, 8, 9 -> {
                String text = rs.getString(index);
                if (text == null) {
                    return PhpNull.INSTANCE;
                }
                try {
                    return Long.parseLong(text);
                } catch (NumberFormatException e) {
                    return PhpStrings.fromJava(text);
                }
            }
            case 4, 5 -> {
                String text = rs.getString(index);
                if (text == null) {
                    return PhpNull.INSTANCE;
                }
                try {
                    return Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    return PhpStrings.fromJava(text);
                }
            }
            case 15, 245, 247, 248, 249, 250, 251, 252, 253, 254, 255 -> {
                byte[] bytes = rs.getBytes(index);
                return bytes == null ? PhpNull.INSTANCE : PhpStrings.of(bytes);
            }
            default -> {
                String text = rs.getString(index);
                return text == null ? PhpNull.INSTANCE : PhpStrings.fromJava(text);
            }
        }
    }

    // ------------------------------------------------------------------ fetching

    private static void validateFetchMode(long mode, String fn) {
        long base = mode & ~(FETCH_GROUP | FETCH_UNIQUE | FETCH_PROPS_LATE | FETCH_CLASSTYPE | FETCH_SERIALIZE);
        if ((mode & (FETCH_PROPS_LATE | FETCH_CLASSTYPE | FETCH_SERIALIZE)) != 0 && base != FETCH_CLASS) {
            throw new PhpError(PhpError.VALUE_ERROR, fn + "(): Argument #1 ($mode) cannot use PDO::FETCH_CLASSTYPE,"
                    + " PDO::FETCH_PROPS_LATE, or PDO::FETCH_SERIALIZE fetch flags with a fetch mode other than"
                    + " PDO::FETCH_CLASS", null);
        }
    }

    static Object fetch(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDOStatement::fetch", args, 0, 3);
        Stmt st = stmt(ctx, self, "PDOStatement::fetch");
        Dbh dbh = state(ctx).connections.get(st.dbhId);
        if (dbh == null) {
            return false;
        }
        long mode;
        Object[] extra;
        if (args.length > 0 && args[0] != PhpNull.INSTANCE) {
            mode = PhpOps.toLong(args[0]);
            extra = new Object[Math.max(0, args.length - 1)];
            System.arraycopy(args, 1, extra, 0, extra.length);
        } else {
            mode = st.fetchMode >= 0 ? st.fetchMode : dbh.defaultFetch;
            extra = st.fetchMode >= 0 ? st.fetchArgs : new Object[0];
        }
        if (mode == FETCH_DEFAULT) {
            mode = st.fetchMode >= 0 ? st.fetchMode : dbh.defaultFetch;
            extra = st.fetchMode >= 0 ? st.fetchArgs : new Object[0];
        }
        validateFetchMode(mode, "PDOStatement::fetch");
        if (st.position >= st.rows.size()) {
            return false;
        }
        Object[] row = st.rows.get(st.position);
        Object result;
        try {
            result = convertRow(ctx, dbh, st, row, st.position, mode, extra, "PDOStatement::fetch");
        } catch (RuntimeException e) {
            throw e;
        }
        st.position++;
        return result;
    }

    static Object fetchAll(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDOStatement::fetchAll", args, 0, 256);
        Stmt st = stmt(ctx, self, "PDOStatement::fetchAll");
        Dbh dbh = state(ctx).connections.get(st.dbhId);
        if (dbh == null) {
            return new PhpArray();
        }
        long mode;
        Object[] extra;
        if (args.length > 0 && args[0] != PhpNull.INSTANCE) {
            mode = PhpOps.toLong(args[0]);
            extra = new Object[Math.max(0, args.length - 1)];
            System.arraycopy(args, 1, extra, 0, extra.length);
        } else {
            mode = st.fetchMode >= 0 ? st.fetchMode : dbh.defaultFetch;
            extra = st.fetchMode >= 0 ? st.fetchArgs : new Object[0];
        }
        if (mode == FETCH_DEFAULT) {
            mode = st.fetchMode >= 0 ? st.fetchMode : dbh.defaultFetch;
            extra = st.fetchMode >= 0 ? st.fetchArgs : new Object[0];
        }
        validateFetchMode(mode, "PDOStatement::fetchAll");
        long baseMode = mode & ~(FETCH_GROUP | FETCH_UNIQUE | FETCH_PROPS_LATE | FETCH_CLASSTYPE | FETCH_SERIALIZE);
        if (baseMode == FETCH_INTO) {
            throw new PhpError(PhpError.VALUE_ERROR,
                    "PDOStatement::fetchAll(): Argument #1 ($mode) PDO::FETCH_INTO cannot be used with PDOStatement::fetchAll()",
                    null);
        }
        long base = baseMode;
        if (base == 0) {
            base = FETCH_BOTH;
        }
        boolean group = (mode & FETCH_GROUP) != 0;
        boolean unique = (mode & FETCH_UNIQUE) != 0;
        if (base == FETCH_KEY_PAIR) {
            if (st.position < st.rows.size() && st.cols.size() != 2) {
                throw pdoException(ctx, "SQLSTATE[HY000]: General error: PDO::FETCH_KEY_PAIR fetch mode requires"
                        + " the result set to contain exactly 2 columns.", "HY000",
                        errorInfoArray("HY000", 0, "PDO::FETCH_KEY_PAIR fetch mode requires the result set to"
                                + " contain exactly 2 columns.", false));
            }
            PhpArray out = new PhpArray();
            while (st.position < st.rows.size()) {
                Object[] row = converted(ctx, dbh, st, st.position);
                st.position++;
                out.put(row[0], row[1]);
            }
            return out;
        }
        PhpArray out = new PhpArray();
        while (st.position < st.rows.size()) {
            Object[] row = converted(ctx, dbh, st, st.position);
            int index = st.position;
            st.position++;
            Object key = group || unique ? row[0] : null;
            Object value = convertConverted(ctx, dbh, st, row, index, base, extra, group || unique,
                    "PDOStatement::fetchAll");
            if (group) {
                Object bucket = out.get(key);
                PhpArray list;
                if (bucket instanceof PhpArray arr) {
                    list = arr;
                } else {
                    list = new PhpArray();
                    out.put(key, list);
                }
                list.append(value);
            } else if (unique) {
                out.put(key, value);
            } else {
                out.append(value);
            }
        }
        return out;
    }

    private static Object[] converted(PhpContext ctx, Dbh dbh, Stmt st, int index) {
        Object[] row = st.rows.get(index);
        Object[] out = new Object[row.length];
        for (int i = 0; i < row.length; i++) {
            out[i] = fetchValue(dbh, row[i]);
        }
        return out;
    }

    private static Object convertRow(PhpContext ctx, Dbh dbh, Stmt st, Object[] row, int index, long mode,
                                     Object[] extra, String fn) {
        Object[] converted = new Object[row.length];
        for (int i = 0; i < row.length; i++) {
            converted[i] = fetchValue(dbh, row[i]);
        }
        return convertConverted(ctx, dbh, st, converted, index, mode, extra, false, fn);
    }

    private static Object convertConverted(PhpContext ctx, Dbh dbh, Stmt st, Object[] row, int index,
                                           long mode, Object[] extra, boolean dropFirst, String fn) {
        long base = mode & ~(FETCH_GROUP | FETCH_UNIQUE | FETCH_PROPS_LATE | FETCH_CLASSTYPE | FETCH_SERIALIZE);
        if (base == 0) {
            base = FETCH_BOTH;
        }
        boolean propsLate = (mode & FETCH_PROPS_LATE) != 0;
        return switch ((int) base) {
            case (int) FETCH_ASSOC -> assoc(dbh, st, row, dropFirst, false);
            case (int) FETCH_NUM -> numeric(row, dropFirst);
            case (int) FETCH_BOTH -> both(dbh, st, row, dropFirst);
            case (int) FETCH_OBJ -> object(ctx, dbh, st, row, dropFirst);
            case (int) FETCH_NAMED -> assoc(dbh, st, row, dropFirst, true);
            case (int) FETCH_LAZY -> lazyRow(ctx, dbh, st, row, dropFirst);
            case (int) FETCH_COLUMN -> columnValue(row, extra, dropFirst);
            case (int) FETCH_BOUND -> boundRow(ctx, st, row, index);
            case (int) FETCH_CLASS -> fetchedClass(ctx, dbh, st, row, dropFirst, extra, propsLate, fn);
            case (int) FETCH_INTO -> into(ctx, dbh, st, row, dropFirst, extra, fn);
            default -> assoc(dbh, st, row, dropFirst, false);
        };
    }

    private static Object columnValue(Object[] row, Object[] extra, boolean dropFirst) {
        int column = extra.length > 0 && extra[0] != PhpNull.INSTANCE ? (int) PhpOps.toLong(extra[0]) : 0;
        if (dropFirst) {
            column++;
        }
        if (column < 0 || column >= row.length) {
            throw new PhpError(PhpError.VALUE_ERROR, "Invalid column index", null);
        }
        return row[column];
    }

    private static String columnName(Dbh dbh, Stmt st, int i) {
        String name = st.cols.get(i).name();
        if (dbh.attrCase == CASE_LOWER) {
            return PhpStrings.lower(name);
        }
        if (dbh.attrCase == CASE_UPPER) {
            return PhpStrings.upper(name);
        }
        return name;
    }

    private static PhpArray assoc(Dbh dbh, Stmt st, Object[] row, boolean dropFirst, boolean named) {
        PhpArray out = new PhpArray();
        int start = dropFirst ? 1 : 0;
        for (int i = start; i < row.length; i++) {
            String name = columnName(dbh, st, i);
            if (named && out.containsKey(name)) {
                Object previous = out.get(name);
                PhpArray values;
                if (previous instanceof PhpArray arr) {
                    values = arr;
                } else {
                    values = new PhpArray();
                    values.append(previous);
                    out.put(name, values);
                }
                values.append(row[i]);
            } else {
                out.put(name, row[i]);
            }
        }
        return out;
    }

    private static PhpArray numeric(Object[] row, boolean dropFirst) {
        PhpArray out = new PhpArray();
        int start = dropFirst ? 1 : 0;
        long key = 0;
        for (int i = start; i < row.length; i++) {
            out.put(key++, row[i]);
        }
        return out;
    }

    private static PhpArray both(Dbh dbh, Stmt st, Object[] row, boolean dropFirst) {
        PhpArray out = new PhpArray();
        int start = dropFirst ? 1 : 0;
        for (int i = start; i < row.length; i++) {
            out.put(columnName(dbh, st, i), row[i]);
            out.put((long) i, row[i]);
        }
        return out;
    }

    private static PhpObject object(PhpContext ctx, Dbh dbh, Stmt st, Object[] row, boolean dropFirst) {
        PhpObject obj = newObject(ctx, "stdClass");
        int start = dropFirst ? 1 : 0;
        for (int i = start; i < row.length; i++) {
            set(obj, columnName(dbh, st, i), row[i]);
        }
        return obj;
    }

    private static PhpObject lazyRow(PhpContext ctx, Dbh dbh, Stmt st, Object[] row, boolean dropFirst) {
        PhpObject obj = newObject(ctx, "PDORow");
        set(obj, "queryString", st.sql);
        int start = dropFirst ? 1 : 0;
        for (int i = start; i < row.length; i++) {
            set(obj, columnName(dbh, st, i), row[i]);
        }
        return obj;
    }

    private static Object boundRow(PhpContext ctx, Stmt st, Object[] row, int index) {
        boolean any = false;
        for (BoundColumn bc : st.boundColumns.values()) {
            int column = bc.index;
            if (column < 0 && bc.name != null) {
                column = -1;
                for (int i = 0; i < st.cols.size(); i++) {
                    if (st.cols.get(i).name().equalsIgnoreCase(bc.name)) {
                        column = i;
                        break;
                    }
                }
            }
            if (column < 0 || column >= row.length) {
                continue;
            }
            Object value = row[column];
            if (bc.ref != null) {
                bc.ref.setValue(boundValue(value, bc.type));
            }
            any = true;
        }
        return any;
    }

    private static Object boundValue(Object value, long type) {
        if (value == PhpNull.INSTANCE) {
            return value;
        }
        return switch ((int) type) {
            case (int) PARAM_INT -> PhpOps.toLong(value);
            case (int) PARAM_BOOL -> PhpOps.toBool(value);
            case (int) PARAM_STR -> PhpOps.toStr(value);
            default -> value;
        };
    }

    private static Object fetchedClass(PhpContext ctx, Dbh dbh, Stmt st, Object[] row, boolean dropFirst,
                                       Object[] extra, boolean propsLate, String fn) {
        String className = null;
        Object[] ctorArgs = new Object[0];
        if (extra.length > 0 && extra[0] instanceof String name && !name.isEmpty()) {
            className = name;
        } else if (st.fetchMode == FETCH_CLASS && st.fetchArgs.length > 0 && st.fetchArgs[0] instanceof String name) {
            className = name;
        }
        if (extra.length > 1 && extra[1] instanceof PhpArray args) {
            ctorArgs = values(args);
        } else if (st.fetchMode == FETCH_CLASS && st.fetchArgs.length > 1 && st.fetchArgs[1] instanceof PhpArray args) {
            ctorArgs = values(args);
        }
        if (className == null) {
            throw pdoException(ctx, "SQLSTATE[HY000]: General error: No fetch class specified", "HY000",
                    errorInfoArray("HY000", 0, "No fetch class specified", false));
        }
        PhpClass cls = ctx.lookupOrAutoloadClass(className);
        if (cls == null || cls.isInterface() || cls.isTrait() || cls.isAbstract()) {
            throw new PhpError(PhpError.TYPE_ERROR, fn + "(): Argument #2 must be a valid class", null);
        }
        PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
        cls.populateDefaults(obj);
        if (!propsLate) {
            applyClassProps(ctx, dbh, st, obj, row, dropFirst);
        }
        PhpClass.MethodEntry ctor = cls.constructMethod();
        if (ctor != null) {
            Object[] callArgs = new Object[2 + ctorArgs.length];
            callArgs[0] = obj;
            callArgs[1] = cls;
            System.arraycopy(ctorArgs, 0, callArgs, 2, ctorArgs.length);
            new DispatchCallNode().callMethod(ctor, callArgs);
        }
        if (propsLate) {
            applyClassProps(ctx, dbh, st, obj, row, dropFirst);
        }
        return obj;
    }

    private static void applyClassProps(PhpContext ctx, Dbh dbh, Stmt st, PhpObject obj, Object[] row,
                                        boolean dropFirst) {
        String scope = PhpStrings.lower(obj.phpClass().name());
        int start = dropFirst ? 1 : 0;
        for (int i = start; i < row.length; i++) {
            String name = columnName(dbh, st, i);
            PhpClass.PropertyResolution resolved = obj.phpClass().resolveProperty(ctx, name, scope);
            set(obj, resolved.storageKey(), row[i]);
        }
    }

    private static Object into(PhpContext ctx, Dbh dbh, Stmt st, Object[] row, boolean dropFirst,
                               Object[] extra, String fn) {
        if (extra.length == 0 || !(extra[0] instanceof PhpObject obj)) {
            throw new PhpError(PhpError.TYPE_ERROR, fn + "(): Argument #2 must be of type object", null);
        }
        int start = dropFirst ? 1 : 0;
        for (int i = start; i < row.length; i++) {
            String name = columnName(dbh, st, i);
            PhpClass.PropertyResolution resolved = obj.phpClass().resolveProperty(ctx, name, null);
            if (!resolved.accessible()) {
                String kind = resolved.visibility() == Visibility.PROTECTED ? "protected" : "private";
                throw new PhpError("Cannot access " + kind + " property "
                        + obj.phpClass().name() + "::$" + name);
            }
            set(obj, resolved.storageKey(), row[i]);
        }
        return obj;
    }

    private static Object[] values(PhpArray arr) {
        Object[] out = new Object[arr.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = arr.get((long) i);
        }
        return out;
    }

    private static Object fetchValue(Dbh dbh, Object value) {
        if (value == PhpNull.INSTANCE) {
            return dbh.oracleNulls == NULL_TO_STRING ? "" : PhpNull.INSTANCE;
        }
        Object out = value;
        if (dbh.stringify) {
            if (value instanceof Long || value instanceof Double) {
                out = PhpOps.toStr(value);
            }
        }
        if (dbh.oracleNulls == NULL_EMPTY_STRING && out instanceof String s && s.isEmpty()) {
            return PhpNull.INSTANCE;
        }
        return out;
    }

    // ------------------------------------------------------------------ statement metadata

    static Object fetchColumn(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDOStatement::fetchColumn", args, 0, 1);
        Stmt st = stmt(ctx, self, "PDOStatement::fetchColumn");
        Dbh dbh = state(ctx).connections.get(st.dbhId);
        if (dbh == null) {
            return false;
        }
        int column = args.length > 0 ? (int) PhpOps.toLong(args[0]) : 0;
        if (column < 0) {
            throw new PhpError(PhpError.VALUE_ERROR, "Column index must be greater than or equal to 0", null);
        }
        if (column >= st.cols.size()) {
            throw new PhpError(PhpError.VALUE_ERROR, "Invalid column index", null);
        }
        if (st.position >= st.rows.size()) {
            return false;
        }
        Object value = fetchValue(dbh, st.rows.get(st.position)[column]);
        st.position++;
        return value;
    }

    static Object fetchObject(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDOStatement::fetchObject", args, 0, 2);
        Stmt st = stmt(ctx, self, "PDOStatement::fetchObject");
        Dbh dbh = state(ctx).connections.get(st.dbhId);
        if (dbh == null || st.position >= st.rows.size()) {
            return false;
        }
        String className = args.length > 0 && args[0] != PhpNull.INSTANCE ? PhpOps.toStr(args[0]) : "stdClass";
        Object[] ctorArgs = args.length > 1 && args[1] instanceof PhpArray arr ? values(arr) : new Object[0];
        Object[] converted = converted(ctx, dbh, st, st.position);
        st.position++;
        PhpClass cls = ctx.lookupOrAutoloadClass(className);
        if (cls == null) {
            throw new PhpError(PhpError.TYPE_ERROR,
                    "PDOStatement::fetchObject(): Argument #1 ($class) must be a valid class", null);
        }
        PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
        cls.populateDefaults(obj);
        String scope = PhpStrings.lower(cls.name());
        for (int i = 0; i < converted.length; i++) {
            String name = columnName(dbh, st, i);
            PhpClass.PropertyResolution resolved = cls.resolveProperty(ctx, name, scope);
            set(obj, resolved.storageKey(), converted[i]);
        }
        PhpClass.MethodEntry ctor = cls.constructMethod();
        if (ctor != null) {
            Object[] callArgs = new Object[2 + ctorArgs.length];
            callArgs[0] = obj;
            callArgs[1] = cls;
            System.arraycopy(ctorArgs, 0, callArgs, 2, ctorArgs.length);
            new DispatchCallNode().callMethod(ctor, callArgs);
        }
        return obj;
    }

    static Object rowCount(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDOStatement::rowCount", args, 0, 0);
        Stmt st = stmt(ctx, self, "PDOStatement::rowCount");
        Dbh dbh = state(ctx).connections.get(st.dbhId);
        if (dbh != null && !dbh.buffered && st.executed && !st.rows.isEmpty()) {
            return 0L;
        }
        return st.rowCount;
    }

    static Object columnCount(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDOStatement::columnCount", args, 0, 0);
        Stmt st = stmt(ctx, self, "PDOStatement::columnCount");
        return (long) st.cols.size();
    }

    static Object closeCursor(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDOStatement::closeCursor", args, 0, 0);
        Stmt st = stmt(ctx, self, "PDOStatement::closeCursor");
        st.position = st.rows.size();
        return true;
    }

    static Object nextRowset(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDOStatement::nextRowset", args, 0, 0);
        stmt(ctx, self, "PDOStatement::nextRowset");
        return false;
    }

    static Object setFetchMode(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDOStatement::setFetchMode", args, 1, 256);
        Stmt st = stmt(ctx, self, "PDOStatement::setFetchMode");
        long mode = PhpOps.toLong(args[0]);
        validateFetchMode(mode, "PDOStatement::setFetchMode");
        st.fetchMode = mode;
        Object[] extra = new Object[Math.max(0, args.length - 1)];
        System.arraycopy(args, 1, extra, 0, extra.length);
        st.fetchArgs = extra;
        return true;
    }

    static Object statementErrorCode(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDOStatement::errorCode", args, 0, 0);
        return stmt(ctx, self, "PDOStatement::errorCode").errorCode;
    }

    static Object statementErrorInfo(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDOStatement::errorInfo", args, 0, 0);
        Stmt st = stmt(ctx, self, "PDOStatement::errorInfo");
        if (st.errorInfo == null) {
            PhpArray ok = new PhpArray();
            ok.append("00000");
            ok.append(PhpNull.INSTANCE);
            ok.append(PhpNull.INSTANCE);
            return ok;
        }
        return st.errorInfo;
    }

    static Object statementGetAttribute(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDOStatement::getAttribute", args, 1, 1);
        Stmt st = stmt(ctx, self, "PDOStatement::getAttribute");
        long attr = PhpOps.toLong(args[0]);
        Dbh dbh = state(ctx).connections.get(st.dbhId);
        if (attr == ATTR_EMULATE_PREPARES) {
            return st.direct ? (dbh != null && dbh.emulate) : st.emulated;
        }
        long errmode = dbh == null ? ERRMODE_EXCEPTION : dbh.errmode;
        return unsupportedGet(ctx, errmode, "PDOStatement::getAttribute",
                "This driver doesn't support getting attributes");
    }

    static Object statementSetAttribute(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDOStatement::setAttribute", args, 2, 2);
        Stmt st = stmt(ctx, self, "PDOStatement::setAttribute");
        Dbh dbh = state(ctx).connections.get(st.dbhId);
        long errmode = dbh == null ? ERRMODE_EXCEPTION : dbh.errmode;
        String detail = "This driver doesn't support setting attributes";
        String message = exceptionMessage("IM001", 0, detail);
        if (errmode == ERRMODE_EXCEPTION) {
            throw pdoException(ctx, message, "IM001", errorInfoArray("IM001", 0, detail, false));
        }
        ctx.warning("PDOStatement::setAttribute(): " + message, 0);
        return false;
    }

    static Object getColumnMeta(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDOStatement::getColumnMeta", args, 1, 1);
        Stmt st = stmt(ctx, self, "PDOStatement::getColumnMeta");
        int index = (int) PhpOps.toLong(args[0]);
        if (index < 0 || index >= st.cols.size()) {
            return false;
        }
        Dbh dbh = state(ctx).connections.get(st.dbhId);
        long attrCase = dbh == null ? CASE_NATURAL : dbh.attrCase;
        MysqliFunctions.FieldInfo f = st.cols.get(index);
        String name = f.name();
        if (attrCase == CASE_LOWER) {
            name = PhpStrings.lower(name);
        } else if (attrCase == CASE_UPPER) {
            name = PhpStrings.upper(name);
        }
        PhpArray meta = new PhpArray();
        meta.put("native_type", nativeType(f.type()));
        meta.put("pdo_type", isPdoIntType(f.type()) ? PARAM_INT : PARAM_STR);
        PhpArray flags = new PhpArray();
        long bits = f.flags();
        if ((bits & 1) != 0) flags.append("not_null");
        if ((bits & 2) != 0) flags.append("primary_key");
        if ((bits & 4) != 0) flags.append("unique_key");
        if ((bits & 8) != 0) flags.append("multiple_key");
        if ((bits & 16) != 0) flags.append("blob");
        meta.put("flags", flags);
        meta.put("table", f.table());
        meta.put("name", name);
        meta.put("len", f.length());
        meta.put("precision", f.decimals());
        return meta;
    }

    private static boolean isPdoIntType(long type) {
        return type == 1 || type == 2 || type == 3 || type == 8 || type == 9 || type == 13 || type == 16;
    }

    private static String nativeType(long type) {
        return switch ((int) type) {
            case 0 -> "DECIMAL";
            case 1 -> "TINY";
            case 2 -> "SHORT";
            case 3 -> "LONG";
            case 4 -> "FLOAT";
            case 5 -> "DOUBLE";
            case 6 -> "NULL";
            case 7 -> "TIMESTAMP";
            case 8 -> "LONGLONG";
            case 9 -> "INT24";
            case 10 -> "DATE";
            case 11 -> "TIME";
            case 12 -> "DATETIME";
            case 13 -> "YEAR";
            case 14 -> "NEWDATE";
            case 15 -> "VARCHAR";
            case 16 -> "BIT";
            case 245 -> "JSON";
            case 246 -> "NEWDECIMAL";
            case 247 -> "ENUM";
            case 248 -> "SET";
            case 249, 250, 251, 252 -> "BLOB";
            case 253 -> "VAR_STRING";
            case 254 -> "STRING";
            case 255 -> "GEOMETRY";
            default -> "STRING";
        };
    }

    static Object debugDumpParams(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDOStatement::debugDumpParams", args, 0, 0);
        Stmt st = stmt(ctx, self, "PDOStatement::debugDumpParams");
        StringBuilder sb = new StringBuilder();
        sb.append("SQL: [").append(byteLength(st.sql)).append("] ").append(st.sql).append('\n');
        if (st.emulated && st.sentSql != null) {
            sb.append("Sent SQL: [").append(byteLength(st.sentSql)).append("] ").append(st.sentSql).append('\n');
        }
        sb.append("Params:  ").append(st.bindings.size()).append('\n');
        int[] positionalOrdinals = new int[st.tokens.size()];
        int positional = 0;
        for (int i = 0; i < st.tokens.size(); i++) {
            positionalOrdinals[i] = st.tokens.get(i).named ? -1 : positional++;
        }
        for (var entry : st.bindings.entrySet()) {
            int key = entry.getKey();
            Token token = key >= 0 && key < st.tokens.size() ? st.tokens.get(key) : new Token(false, null);
            int position = token.named ? -1 : positionalOrdinals[key];
            long type = entry.getValue().type;
            appendDumpParam(sb, token, st.emulated ? position : key, position, type);
        }
        ctx.echo(sb.toString());
        return PhpNull.INSTANCE;
    }

    private static void appendDumpParam(StringBuilder sb, Token token, int paramno, int position, long type) {
        if (token.named) {
            int length = byteLength(":" + token.name);
            sb.append("Key: Name: [").append(length).append("] :").append(token.name).append('\n');
            sb.append("paramno=").append(paramno).append('\n');
            sb.append("name=[").append(length).append("] \":").append(token.name).append("\"\n");
        } else {
            sb.append("Key: Position #").append(position).append(":\n");
            sb.append("paramno=").append(position).append('\n');
            sb.append("name=[0] \"\"\n");
        }
        sb.append("is_param=1\n");
        sb.append("param_type=").append(type).append('\n');
    }

    private static int byteLength(String s) {
        return PhpStrings.bytes(s).length;
    }

    // ------------------------------------------------------------------ iteration

    static Object getIterator(PhpContext ctx, PhpObject self, Object[] args) {
        requireArgs("PDOStatement::getIterator", args, 0, 0);
        Stmt st = stmt(ctx, self, "PDOStatement::getIterator");
        PhpObject it = newObject(ctx, "InternalIterator");
        IteratorState is = new IteratorState();
        is.stmtId = self.id();
        state(ctx).iterators.put(it.id(), is);
        return it;
    }

    private static IteratorState iterator(PhpContext ctx, PhpObject self, String fn) {
        IteratorState is = state(ctx).iterators.get(self.id());
        if (is == null) {
            throw new PhpError(fn + "(): InternalIterator is not initialized");
        }
        return is;
    }

    static Object iteratorRewind(PhpContext ctx, PhpObject self, Object[] args) {
        IteratorState is = iterator(ctx, self, "InternalIterator::rewind");
        is.hasPending = false;
        is.pending = null;
        is.index = -1;
        return PhpNull.INSTANCE;
    }

    static Object iteratorValid(PhpContext ctx, PhpObject self, Object[] args) {
        IteratorState is = iterator(ctx, self, "InternalIterator::valid");
        fillPending(ctx, is);
        return is.hasPending;
    }

    static Object iteratorCurrent(PhpContext ctx, PhpObject self, Object[] args) {
        IteratorState is = iterator(ctx, self, "InternalIterator::current");
        fillPending(ctx, is);
        return is.hasPending ? is.pending : PhpNull.INSTANCE;
    }

    static Object iteratorKey(PhpContext ctx, PhpObject self, Object[] args) {
        IteratorState is = iterator(ctx, self, "InternalIterator::key");
        fillPending(ctx, is);
        return is.hasPending ? is.pendingKey : PhpNull.INSTANCE;
    }

    static Object iteratorNext(PhpContext ctx, PhpObject self, Object[] args) {
        IteratorState is = iterator(ctx, self, "InternalIterator::next");
        is.hasPending = false;
        is.pending = null;
        return PhpNull.INSTANCE;
    }

    private static void fillPending(PhpContext ctx, IteratorState is) {
        if (is.hasPending) {
            return;
        }
        Stmt st = state(ctx).statements.get(is.stmtId);
        Dbh dbh = st == null ? null : state(ctx).connections.get(st.dbhId);
        if (st == null || dbh == null || st.position >= st.rows.size()) {
            return;
        }
        long mode = st.fetchMode >= 0 ? st.fetchMode : dbh.defaultFetch;
        Object[] extra = st.fetchMode >= 0 ? st.fetchArgs : new Object[0];
        Object row = convertRow(ctx, dbh, st, st.rows.get(st.position), st.position, mode, extra,
                "InternalIterator::current");
        is.index = st.position;
        st.position++;
        is.pendingKey = (long) is.index;
        is.pending = row;
        is.hasPending = true;
    }
}
