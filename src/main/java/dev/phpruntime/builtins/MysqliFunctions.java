package dev.phpruntime.builtins;

import com.oracle.truffle.api.object.DynamicObject;
import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpClassTemplate;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpStrings;
import dev.phpruntime.runtime.Visibility;

import java.lang.reflect.Field;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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
 * <p>TODO: connections are opened per request and closed at its end; a pool shared across
 * requests would save the connect round trips.
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

    // ------------------------------------------------------------------ state

    /** Per-request mysqli state. */
    static final class State {
        final Map<Long, Link> links = new HashMap<>();
        final Map<Long, Result> results = new HashMap<>();
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

        @Override
        public void close() throws SQLException {
            closed = true;
            if (conn != null) {
                Connection c = conn;
                conn = null;
                c.close();
            }
        }
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

    private static PhpClassTemplate mysqliTemplate(PhpContext ctx) {
        return ctx.language().mysqliTemplate(linkProps());
    }

    private static PhpClassTemplate resultTemplate(PhpContext ctx) {
        return ctx.language().mysqliResultTemplate(RESULT_PROPS);
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

    private static void syncResult(PhpObject obj, Result r) {
        set(obj, "current_field", (long) r.fieldPosition);
        set(obj, "field_count", (long) r.fields.size());
        set(obj, "num_rows", (long) r.rows.size());
        set(obj, "type", 0L);
    }

    private static Object serverVersion(String info) {
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

    /** A link that must be connected (most functions). */
    private static Link connected(PhpContext ctx, Object v, String fn) {
        Link l = link(ctx, v, fn);
        if (l.conn == null) {
            throw new PhpError("mysqli object is not fully initialized");
        }
        return l;
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
    @SuppressWarnings("unchecked")
    private static <T> T blocking(PhpContext ctx, SqlCall<T> call) throws SQLException {
        Object[] out = new Object[1];
        SQLException[] err = new SQLException[1];
        RuntimeException[] rte = new RuntimeException[1];
        boolean[] done = new boolean[1];
        ctx.request().scheduler.blocking(null, () -> {
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

    private static String message(SQLException e) {
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
            String db = PhpStrings.toJava(PhpOps.toStr(a[1]));
            try {
                blocking(ctx, () -> {
                    l.conn.setCatalog(db);
                    return null;
                });
                clearError(l);
                sync(ctx, (PhpObject) a[0], l);
                return true;
            } catch (SQLException e) {
                fail(ctx, l, e, "mysqli_select_db");
                sync(ctx, (PhpObject) a[0], l);
                return false;
            }
        });
        Builtins.def("mysqli_set_charset", 2, 2, (ctx, a) -> {
            Link l = connected(ctx, a[0], "mysqli_set_charset");
            String cs = PhpOps.toStr(a[1]);
            try {
                execute(ctx, l, "SET NAMES " + cs);
                l.charset = cs;
                clearError(l);
                return true;
            } catch (SQLException e) {
                fail(ctx, l, e, "mysqli_set_charset");
                return false;
            } finally {
                sync(ctx, (PhpObject) a[0], l);
            }
        });
        Builtins.def("mysqli_character_set_name", 1, 1, (ctx, a) -> connected(ctx, a[0], "mysqli_character_set_name").charset);
        Builtins.def("mysqli_query", 2, 3, MysqliFunctions::query);
        Builtins.def("mysqli_real_query", 2, 2, (ctx, a) -> {
            Object r = query(ctx, a);
            return r != Boolean.FALSE;
        });
        Builtins.def("mysqli_real_escape_string", 2, 2, (ctx, a) -> {
            connected(ctx, a[0], "mysqli_real_escape_string");
            return escape(PhpOps.toStr(a[1]));
        });
        Builtins.def("mysqli_escape_string", 2, 2, (ctx, a) -> {
            connected(ctx, a[0], "mysqli_escape_string");
            return escape(PhpOps.toStr(a[1]));
        });
        Builtins.def("mysqli_error", 1, 1, (ctx, a) -> link(ctx, a[0], "mysqli_error").error);
        Builtins.def("mysqli_errno", 1, 1, (ctx, a) -> (long) link(ctx, a[0], "mysqli_errno").errno);
        Builtins.def("mysqli_sqlstate", 1, 1, (ctx, a) -> link(ctx, a[0], "mysqli_sqlstate").sqlstate);
        Builtins.def("mysqli_affected_rows", 1, 1, (ctx, a) -> connected(ctx, a[0], "mysqli_affected_rows").affectedRows);
        Builtins.def("mysqli_insert_id", 1, 1, (ctx, a) -> connected(ctx, a[0], "mysqli_insert_id").insertId);
        Builtins.def("mysqli_field_count", 1, 1, (ctx, a) -> (long) connected(ctx, a[0], "mysqli_field_count").fieldCount);
        Builtins.def("mysqli_more_results", 1, 1, (ctx, a) -> {
            connected(ctx, a[0], "mysqli_more_results");
            return false;
        });
        Builtins.def("mysqli_next_result", 1, 1, (ctx, a) -> {
            connected(ctx, a[0], "mysqli_next_result");
            return false;
        });
        Builtins.def("mysqli_get_server_info", 1, 1, (ctx, a) -> connected(ctx, a[0], "mysqli_get_server_info").serverInfo);
        Builtins.def("mysqli_get_server_version", 1, 1, (ctx, a) ->
                serverVersion(connected(ctx, a[0], "mysqli_get_server_version").serverInfo));
        Builtins.def("mysqli_get_client_info", 0, 1, (ctx, a) -> CLIENT_INFO);
        Builtins.def("mysqli_get_client_version", 0, 0, (ctx, a) -> CLIENT_VERSION);
        Builtins.def("mysqli_get_host_info", 1, 1, (ctx, a) -> connected(ctx, a[0], "mysqli_get_host_info").host + " via TCP/IP");
        Builtins.def("mysqli_ping", 1, 1, (ctx, a) -> {
            Link l = connected(ctx, a[0], "mysqli_ping");
            try {
                execute(ctx, l, "DO 1");
                return true;
            } catch (SQLException e) {
                fail(ctx, l, e, "mysqli_ping");
                return false;
            }
        });
        Builtins.def("mysqli_close", 1, 1, (ctx, a) -> {
            Link l = connected(ctx, a[0], "mysqli_close");
            try {
                blocking(ctx, () -> {
                    l.close();
                    return null;
                });
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
        final Link l = link;
        try {
            Connection conn = blocking(ctx, () -> {
                Connection c = new org.mariadb.jdbc.Driver().connect(url, props);
                if (c == null) {
                    throw new SQLException("Unsupported connection URL", "HY000", 2002);
                }
                return c;
            });
            l.conn = conn;
            l.closed = false;
            l.host = host;
            l.charset = "utf8mb4";
            l.serverInfo = PhpStrings.fromJava(blocking(ctx, () -> conn.getMetaData().getDatabaseProductVersion()));
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

    private static String connectFailureMessage(SQLException e, String host) {
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

    private static void execute(PhpContext ctx, Link l, String sql) throws SQLException {
        String javaSql = MysqliSql.toJava(sql);
        blocking(ctx, () -> {
            try (Statement st = l.conn.createStatement()) {
                st.execute(javaSql);
            }
            return null;
        });
    }

    /** Result of running one statement, built on the blocking side. */
    private record Outcome(Result result, long updateCount, long insertId) {
    }

    private static Object query(PhpContext ctx, Object[] a) {
        PhpObject obj = linkObject(ctx, a[0], "mysqli_query");
        Link l = connected(ctx, obj, "mysqli_query");
        String sql = PhpOps.toStr(a[1]);
        if (sql.isEmpty()) {
            throw new PhpError("ValueError", "mysqli_query(): Argument #2 ($query) cannot be empty", null);
        }
        try {
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
            if (out.result() != null) {
                Result r = out.result();
                l.affectedRows = r.rows.size();
                l.insertId = 0;
                l.fieldCount = r.fields.size();
                PhpObject ro = newObject(ctx, resultTemplate(ctx));
                state(ctx).results.put(ro.id(), r);
                syncResult(ro, r);
                sync(ctx, obj, l);
                return ro;
            }
            l.affectedRows = out.updateCount();
            l.insertId = out.insertId();
            l.fieldCount = 0;
            sync(ctx, obj, l);
            return true;
        } catch (SQLException e) {
            fail(ctx, l, e, "mysqli_query");
            sync(ctx, obj, l);
            return false;
        }
    }

    private static Field metadataField;
    private static Field charsetField;
    private static boolean reflectionTried;

    /** The driver's own column descriptions, which carry MySQL's type codes and flags; null if unavailable. */
    private static synchronized Object[] driverColumns(ResultSet rs) {
        try {
            if (!reflectionTried) {
                reflectionTried = true;
                metadataField = Class.forName("org.mariadb.jdbc.client.result.Result").getDeclaredField("metadataList");
                metadataField.setAccessible(true);
                charsetField = Class.forName("org.mariadb.jdbc.message.server.ColumnDefinitionPacket").getDeclaredField("charset");
                charsetField.setAccessible(true);
            }
            if (metadataField == null) {
                return null;
            }
            ResultSet target = rs.isWrapperFor(ResultSet.class) ? rs.unwrap(ResultSet.class) : rs;
            return (Object[]) metadataField.get(target);
        } catch (ReflectiveOperationException | SQLException | RuntimeException e) {
            metadataField = null;
            return null;
        }
    }

    private static Result read(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        Object[] cols = driverColumns(rs);
        List<FieldInfo> fields = new ArrayList<>(n);
        boolean[] bit = new boolean[n];
        boolean[] bytes = new boolean[n];
        for (int i = 0; i < n; i++) {
            FieldInfo f = cols != null && cols.length == n && cols[i] instanceof org.mariadb.jdbc.client.Column c
                    ? field(c) : field(md, i + 1);
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

    private static FieldInfo field(org.mariadb.jdbc.client.Column c) {
        long charset = 0;
        try {
            if (charsetField != null) {
                charset = ((Number) charsetField.get(c)).longValue();
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

    private static FieldInfo field(ResultSetMetaData md, int i) throws SQLException {
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
