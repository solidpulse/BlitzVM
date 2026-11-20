package dev.phpruntime.builtins;

import java.util.List;
import java.util.Map;

/**
 * The mysqli state a boot snapshot carries: one descriptor per link (connect parameters and the
 * session setup boot performed on it) and one per result that was still open. Nothing here is a
 * live JDBC object, so the snapshot stays read-only and may be restored on several threads.
 */
public final class MysqliSnapshot {

    /** One step of session setup, in the order boot performed it. */
    public enum SetupKind {
        /** A statement replayed verbatim (SET NAMES, SET SESSION ...). */
        SQL,
        /** mysqli_set_charset: replay as SET NAMES. */
        CHARSET,
        /** mysqli_select_db: replay as a catalog change. */
        DATABASE,
        /** mysqli_autocommit: replay as a JDBC autocommit change. */
        AUTOCOMMIT,
        /** mysqli_options called before connecting; its effect is already in the connect parameters. */
        OPTION
    }

    public record Setup(SetupKind kind, String text, String value, boolean flag) {
        static Setup sql(String sql) {
            return new Setup(SetupKind.SQL, sql, null, false);
        }

        static Setup charset(String charset) {
            return new Setup(SetupKind.CHARSET, charset, null, false);
        }

        static Setup database(String database) {
            return new Setup(SetupKind.DATABASE, database, null, false);
        }

        static Setup autocommit(boolean on) {
            return new Setup(SetupKind.AUTOCOMMIT, null, null, on);
        }

        static Setup option(long option, String value) {
            return new Setup(SetupKind.OPTION, Long.toString(option), value, false);
        }
    }

    /** One link as it was when boot finished. */
    public record LinkState(long objectId, String url, Map<String, String> properties, String host,
                            String serverInfo, String charset, String logicalCatalog,
                            String physicalCatalog, Map<Long, Object> options, String initCommand,
                            boolean closed, boolean autocommit, long affectedRows, long insertId,
                            long fieldCount, int errno, String error, String sqlstate,
                            List<Setup> setup) {
    }

    /** A result that boot left open, with its read position at the boundary. */
    public record ResultState(long objectId, List<MysqliFunctions.FieldInfo> fields,
                              List<Object[]> rows, int position, int fieldPosition, boolean freed) {
    }

    final long reportMode;
    final int connectErrno;
    final String connectError;
    final List<LinkState> links;
    final List<ResultState> results;

    MysqliSnapshot(long reportMode, int connectErrno, String connectError, List<LinkState> links,
                   List<ResultState> results) {
        this.reportMode = reportMode;
        this.connectErrno = connectErrno;
        this.connectError = connectError;
        this.links = List.copyOf(links);
        this.results = List.copyOf(results);
    }

    public int linkCount() {
        return links.size();
    }

    public int resultCount() {
        return results.size();
    }
}
