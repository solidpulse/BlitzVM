package dev.phpruntime.builtins;

import dev.phpruntime.PhpLanguage;
import dev.phpruntime.runtime.PhpClassTemplate;
import dev.phpruntime.runtime.PhpExceptions;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.Visibility;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The built-in classes of the PDO extension: {@code PDO}, {@code PDOStatement}, {@code PDORow},
 * {@code PDOException} and the {@code InternalIterator} a statement hands to foreach.
 *
 * <p>The templates are engine-wide like every other built-in class; the per-object Java state
 * lives in the request's {@link PdoFunctions.State}, keyed by object id.
 */
public final class PdoClasses {

    private PdoClasses() {
    }

    // PDO::PARAM_*
    static final long PARAM_NULL = 0;
    static final long PARAM_INT = 1;
    static final long PARAM_STR = 2;
    static final long PARAM_LOB = 3;
    static final long PARAM_STMT = 4;
    static final long PARAM_BOOL = 5;
    static final long PARAM_INPUT_OUTPUT = 2147483648L;
    static final long PARAM_STR_NATL = 1073741824L;
    static final long PARAM_STR_CHAR = 536870912L;

    // PDO::FETCH_*
    static final long FETCH_DEFAULT = 0;
    static final long FETCH_LAZY = 1;
    static final long FETCH_ASSOC = 2;
    static final long FETCH_NUM = 3;
    static final long FETCH_BOTH = 4;
    static final long FETCH_OBJ = 5;
    static final long FETCH_BOUND = 6;
    static final long FETCH_COLUMN = 7;
    static final long FETCH_CLASS = 8;
    static final long FETCH_INTO = 9;
    static final long FETCH_FUNC = 10;
    static final long FETCH_NAMED = 11;
    static final long FETCH_KEY_PAIR = 12;
    static final long FETCH_GROUP = 32;
    static final long FETCH_UNIQUE = 64;
    static final long FETCH_CLASSTYPE = 128;
    static final long FETCH_PROPS_LATE = 256;
    static final long FETCH_SERIALIZE = 512;

    // PDO::ATTR_*
    static final long ATTR_AUTOCOMMIT = 0;
    static final long ATTR_PREFETCH = 1;
    static final long ATTR_TIMEOUT = 2;
    static final long ATTR_ERRMODE = 3;
    static final long ATTR_SERVER_VERSION = 4;
    static final long ATTR_CLIENT_VERSION = 5;
    static final long ATTR_SERVER_INFO = 6;
    static final long ATTR_CONNECTION_STATUS = 7;
    static final long ATTR_CASE = 8;
    static final long ATTR_CURSOR_NAME = 9;
    static final long ATTR_CURSOR = 10;
    static final long ATTR_ORACLE_NULLS = 11;
    static final long ATTR_PERSISTENT = 12;
    static final long ATTR_STATEMENT_CLASS = 13;
    static final long ATTR_FETCH_TABLE_NAMES = 14;
    static final long ATTR_FETCH_CATALOG_NAMES = 15;
    static final long ATTR_DRIVER_NAME = 16;
    static final long ATTR_STRINGIFY_FETCHES = 17;
    static final long ATTR_MAX_COLUMN_LEN = 18;
    static final long ATTR_DEFAULT_FETCH_MODE = 19;
    static final long ATTR_EMULATE_PREPARES = 20;
    static final long ATTR_DEFAULT_STR_PARAM = 21;

    // PDO::ERRMODE_*
    static final long ERRMODE_SILENT = 0;
    static final long ERRMODE_WARNING = 1;
    static final long ERRMODE_EXCEPTION = 2;

    // PDO::CASE_*
    static final long CASE_NATURAL = 0;
    static final long CASE_UPPER = 1;
    static final long CASE_LOWER = 2;

    // PDO::NULL_*
    static final long NULL_NATURAL = 0;
    static final long NULL_EMPTY_STRING = 1;
    static final long NULL_TO_STRING = 2;

    // mysql driver attributes
    static final long MYSQL_ATTR_USE_BUFFERED_QUERY = 1000;
    static final long MYSQL_ATTR_LOCAL_INFILE = 1001;
    static final long MYSQL_ATTR_INIT_COMMAND = 1002;
    static final long MYSQL_ATTR_COMPRESS = 1003;
    static final long MYSQL_ATTR_DIRECT_QUERY = 20;
    static final long MYSQL_ATTR_FOUND_ROWS = 1004;
    static final long MYSQL_ATTR_IGNORE_SPACE = 1005;
    static final long MYSQL_ATTR_SSL_KEY = 1006;
    static final long MYSQL_ATTR_SSL_CERT = 1007;
    static final long MYSQL_ATTR_SSL_CA = 1008;
    static final long MYSQL_ATTR_SSL_CAPATH = 1009;
    static final long MYSQL_ATTR_SSL_CIPHER = 1010;
    static final long MYSQL_ATTR_SERVER_PUBLIC_KEY = 1011;
    static final long MYSQL_ATTR_MULTI_STATEMENTS = 1012;
    static final long MYSQL_ATTR_SSL_VERIFY_SERVER_CERT = 1013;
    static final long MYSQL_ATTR_LOCAL_INFILE_DIRECTORY = 1014;

    /** Every class constant of PDO, with php 8.5's values. */
    private static List<PhpClassTemplate.ConstantDecl> constants() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("PARAM_NULL", PARAM_NULL);
        c.put("PARAM_BOOL", PARAM_BOOL);
        c.put("PARAM_INT", PARAM_INT);
        c.put("PARAM_STR", PARAM_STR);
        c.put("PARAM_LOB", PARAM_LOB);
        c.put("PARAM_STMT", PARAM_STMT);
        c.put("PARAM_INPUT_OUTPUT", PARAM_INPUT_OUTPUT);
        c.put("PARAM_STR_NATL", PARAM_STR_NATL);
        c.put("PARAM_STR_CHAR", PARAM_STR_CHAR);
        c.put("PARAM_EVT_ALLOC", 0L);
        c.put("PARAM_EVT_FREE", 1L);
        c.put("PARAM_EVT_EXEC_PRE", 2L);
        c.put("PARAM_EVT_EXEC_POST", 3L);
        c.put("PARAM_EVT_FETCH_PRE", 4L);
        c.put("PARAM_EVT_FETCH_POST", 5L);
        c.put("PARAM_EVT_NORMALIZE", 6L);
        c.put("FETCH_DEFAULT", FETCH_DEFAULT);
        c.put("FETCH_LAZY", FETCH_LAZY);
        c.put("FETCH_ASSOC", FETCH_ASSOC);
        c.put("FETCH_NUM", FETCH_NUM);
        c.put("FETCH_BOTH", FETCH_BOTH);
        c.put("FETCH_OBJ", FETCH_OBJ);
        c.put("FETCH_BOUND", FETCH_BOUND);
        c.put("FETCH_COLUMN", FETCH_COLUMN);
        c.put("FETCH_CLASS", FETCH_CLASS);
        c.put("FETCH_INTO", FETCH_INTO);
        c.put("FETCH_FUNC", FETCH_FUNC);
        c.put("FETCH_GROUP", FETCH_GROUP);
        c.put("FETCH_UNIQUE", FETCH_UNIQUE);
        c.put("FETCH_KEY_PAIR", FETCH_KEY_PAIR);
        c.put("FETCH_CLASSTYPE", FETCH_CLASSTYPE);
        c.put("FETCH_SERIALIZE", FETCH_SERIALIZE);
        c.put("FETCH_PROPS_LATE", FETCH_PROPS_LATE);
        c.put("FETCH_NAMED", FETCH_NAMED);
        c.put("ATTR_AUTOCOMMIT", ATTR_AUTOCOMMIT);
        c.put("ATTR_PREFETCH", ATTR_PREFETCH);
        c.put("ATTR_TIMEOUT", ATTR_TIMEOUT);
        c.put("ATTR_ERRMODE", ATTR_ERRMODE);
        c.put("ATTR_SERVER_VERSION", ATTR_SERVER_VERSION);
        c.put("ATTR_CLIENT_VERSION", ATTR_CLIENT_VERSION);
        c.put("ATTR_SERVER_INFO", ATTR_SERVER_INFO);
        c.put("ATTR_CONNECTION_STATUS", ATTR_CONNECTION_STATUS);
        c.put("ATTR_CASE", ATTR_CASE);
        c.put("ATTR_CURSOR_NAME", ATTR_CURSOR_NAME);
        c.put("ATTR_CURSOR", ATTR_CURSOR);
        c.put("ATTR_ORACLE_NULLS", ATTR_ORACLE_NULLS);
        c.put("ATTR_PERSISTENT", ATTR_PERSISTENT);
        c.put("ATTR_STATEMENT_CLASS", ATTR_STATEMENT_CLASS);
        c.put("ATTR_FETCH_TABLE_NAMES", ATTR_FETCH_TABLE_NAMES);
        c.put("ATTR_FETCH_CATALOG_NAMES", ATTR_FETCH_CATALOG_NAMES);
        c.put("ATTR_DRIVER_NAME", ATTR_DRIVER_NAME);
        c.put("ATTR_STRINGIFY_FETCHES", ATTR_STRINGIFY_FETCHES);
        c.put("ATTR_MAX_COLUMN_LEN", ATTR_MAX_COLUMN_LEN);
        c.put("ATTR_EMULATE_PREPARES", ATTR_EMULATE_PREPARES);
        c.put("ATTR_DEFAULT_FETCH_MODE", ATTR_DEFAULT_FETCH_MODE);
        c.put("ATTR_DEFAULT_STR_PARAM", ATTR_DEFAULT_STR_PARAM);
        c.put("ERRMODE_SILENT", ERRMODE_SILENT);
        c.put("ERRMODE_WARNING", ERRMODE_WARNING);
        c.put("ERRMODE_EXCEPTION", ERRMODE_EXCEPTION);
        c.put("CASE_NATURAL", CASE_NATURAL);
        c.put("CASE_LOWER", CASE_LOWER);
        c.put("CASE_UPPER", CASE_UPPER);
        c.put("NULL_NATURAL", NULL_NATURAL);
        c.put("NULL_EMPTY_STRING", NULL_EMPTY_STRING);
        c.put("NULL_TO_STRING", NULL_TO_STRING);
        c.put("ERR_NONE", "00000");
        c.put("FETCH_ORI_NEXT", 0L);
        c.put("FETCH_ORI_PRIOR", 1L);
        c.put("FETCH_ORI_FIRST", 2L);
        c.put("FETCH_ORI_LAST", 3L);
        c.put("FETCH_ORI_ABS", 4L);
        c.put("FETCH_ORI_REL", 5L);
        c.put("CURSOR_FWDONLY", 0L);
        c.put("CURSOR_SCROLL", 1L);
        c.put("MYSQL_ATTR_USE_BUFFERED_QUERY", MYSQL_ATTR_USE_BUFFERED_QUERY);
        c.put("MYSQL_ATTR_LOCAL_INFILE", MYSQL_ATTR_LOCAL_INFILE);
        c.put("MYSQL_ATTR_INIT_COMMAND", MYSQL_ATTR_INIT_COMMAND);
        c.put("MYSQL_ATTR_COMPRESS", MYSQL_ATTR_COMPRESS);
        c.put("MYSQL_ATTR_DIRECT_QUERY", MYSQL_ATTR_DIRECT_QUERY);
        c.put("MYSQL_ATTR_FOUND_ROWS", MYSQL_ATTR_FOUND_ROWS);
        c.put("MYSQL_ATTR_IGNORE_SPACE", MYSQL_ATTR_IGNORE_SPACE);
        c.put("MYSQL_ATTR_SSL_KEY", MYSQL_ATTR_SSL_KEY);
        c.put("MYSQL_ATTR_SSL_CERT", MYSQL_ATTR_SSL_CERT);
        c.put("MYSQL_ATTR_SSL_CA", MYSQL_ATTR_SSL_CA);
        c.put("MYSQL_ATTR_SSL_CAPATH", MYSQL_ATTR_SSL_CAPATH);
        c.put("MYSQL_ATTR_SSL_CIPHER", MYSQL_ATTR_SSL_CIPHER);
        c.put("MYSQL_ATTR_SERVER_PUBLIC_KEY", MYSQL_ATTR_SERVER_PUBLIC_KEY);
        c.put("MYSQL_ATTR_MULTI_STATEMENTS", MYSQL_ATTR_MULTI_STATEMENTS);
        c.put("MYSQL_ATTR_SSL_VERIFY_SERVER_CERT", MYSQL_ATTR_SSL_VERIFY_SERVER_CERT);
        c.put("MYSQL_ATTR_LOCAL_INFILE_DIRECTORY", MYSQL_ATTR_LOCAL_INFILE_DIRECTORY);
        List<PhpClassTemplate.ConstantDecl> out = new java.util.ArrayList<>(c.size());
        for (Map.Entry<String, Object> e : c.entrySet()) {
            out.add(new PhpClassTemplate.ConstantDecl(e.getKey(), Visibility.PUBLIC, false, e.getValue(), null));
        }
        return List.copyOf(out);
    }

    /** Adds PDO, PDOStatement, PDORow, PDOException and InternalIterator to the built-in class map. */
    public static void buildTemplates(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        PdoFunctions.disableDriverLogging();
        out.put("pdo", pdoTemplate(language));
        out.put("pdostatement", pdoStatementTemplate(language));
        out.put("pdorow", pdoRowTemplate(language));
        out.put("pdoexception", pdoExceptionTemplate(language));
        out.put("internaliterator", internalIteratorTemplate(language));
    }

    private static PhpClassTemplate.MethodDecl method(PhpLanguage language, String name,
                                                      PhpExceptions.BuiltinMethod impl) {
        String lower = dev.phpruntime.runtime.PhpStrings.lower(name);
        return new PhpClassTemplate.MethodDecl(name, lower, language.methodId(name),
                Visibility.PUBLIC, false, false, false,
                new PhpExceptions.BuiltinMethodRootNode(language, name, impl).getCallTarget());
    }

    private static PhpClassTemplate.MethodDecl staticMethod(PhpLanguage language, String name,
                                                            PhpExceptions.BuiltinMethod impl) {
        String lower = dev.phpruntime.runtime.PhpStrings.lower(name);
        return new PhpClassTemplate.MethodDecl(name, lower, language.methodId(name),
                Visibility.PUBLIC, true, false, false,
                new PhpExceptions.BuiltinMethodRootNode(language, name, impl).getCallTarget());
    }

    private static PhpClassTemplate.MethodDecl refMethod(PhpLanguage language, String name,
                                                         long refMask, PhpExceptions.BuiltinMethod impl) {
        String lower = dev.phpruntime.runtime.PhpStrings.lower(name);
        return new PhpClassTemplate.MethodDecl(name, lower, language.methodId(name),
                Visibility.PUBLIC, false, false, false,
                new PhpExceptions.BuiltinMethodRootNode(language, name, impl, refMask).getCallTarget());
    }

    private static PhpClassTemplate pdoTemplate(PhpLanguage language) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        methods.put("__construct", method(language, "__construct", PdoFunctions::construct));
        methods.put("connect", staticMethod(language, "connect", PdoFunctions::connectStatic));
        methods.put("beginTransaction", method(language, "beginTransaction", PdoFunctions::beginTransaction));
        methods.put("commit", method(language, "commit", PdoFunctions::commit));
        methods.put("errorCode", method(language, "errorCode", PdoFunctions::errorCode));
        methods.put("errorInfo", method(language, "errorInfo", PdoFunctions::errorInfo));
        methods.put("exec", method(language, "exec", PdoFunctions::exec));
        methods.put("getAttribute", method(language, "getAttribute", PdoFunctions::getAttribute));
        methods.put("getAvailableDrivers", staticMethod(language, "getAvailableDrivers",
                PdoFunctions::getAvailableDrivers));
        methods.put("inTransaction", method(language, "inTransaction", PdoFunctions::inTransaction));
        methods.put("lastInsertId", method(language, "lastInsertId", PdoFunctions::lastInsertId));
        methods.put("prepare", method(language, "prepare", PdoFunctions::prepare));
        methods.put("query", method(language, "query", PdoFunctions::query));
        methods.put("quote", method(language, "quote", PdoFunctions::quote));
        methods.put("rollBack", method(language, "rollBack", PdoFunctions::rollBack));
        methods.put("setAttribute", method(language, "setAttribute", PdoFunctions::setAttribute));
        return new PhpClassTemplate(language.nextTemplateId(), "PDO", false, false, false, false, false,
                null, List.of(), List.of(), List.of(), constants(), methods);
    }

    private static PhpClassTemplate pdoStatementTemplate(PhpLanguage language) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        methods.put("bindColumn", refMethod(language, "bindColumn", 1L << 1, PdoFunctions::bindColumn));
        methods.put("bindParam", refMethod(language, "bindParam", 1L << 1, PdoFunctions::bindParam));
        methods.put("bindValue", method(language, "bindValue", PdoFunctions::bindValue));
        methods.put("closeCursor", method(language, "closeCursor", PdoFunctions::closeCursor));
        methods.put("columnCount", method(language, "columnCount", PdoFunctions::columnCount));
        methods.put("debugDumpParams", method(language, "debugDumpParams", PdoFunctions::debugDumpParams));
        methods.put("errorCode", method(language, "errorCode", PdoFunctions::statementErrorCode));
        methods.put("errorInfo", method(language, "errorInfo", PdoFunctions::statementErrorInfo));
        methods.put("execute", method(language, "execute", PdoFunctions::execute));
        methods.put("fetch", method(language, "fetch", PdoFunctions::fetch));
        methods.put("fetchAll", method(language, "fetchAll", PdoFunctions::fetchAll));
        methods.put("fetchColumn", method(language, "fetchColumn", PdoFunctions::fetchColumn));
        methods.put("fetchObject", method(language, "fetchObject", PdoFunctions::fetchObject));
        methods.put("getAttribute", method(language, "getAttribute", PdoFunctions::statementGetAttribute));
        methods.put("getColumnMeta", method(language, "getColumnMeta", PdoFunctions::getColumnMeta));
        methods.put("nextRowset", method(language, "nextRowset", PdoFunctions::nextRowset));
        methods.put("rowCount", method(language, "rowCount", PdoFunctions::rowCount));
        methods.put("setAttribute", method(language, "setAttribute", PdoFunctions::statementSetAttribute));
        methods.put("setFetchMode", method(language, "setFetchMode", PdoFunctions::setFetchMode));
        methods.put("getIterator", method(language, "getIterator", PdoFunctions::getIterator));
        List<PhpClassTemplate.PropertyDecl> props = List.of(
                new PhpClassTemplate.PropertyDecl("queryString", Visibility.PUBLIC, false, "", null));
        return new PhpClassTemplate(language.nextTemplateId(), "PDOStatement", false, false, false, false, false,
                null, List.of("IteratorAggregate"), List.of(), props, List.of(), methods);
    }

    private static PhpClassTemplate pdoRowTemplate(PhpLanguage language) {
        List<PhpClassTemplate.PropertyDecl> props = List.of(
                new PhpClassTemplate.PropertyDecl("queryString", Visibility.PUBLIC, false, "", null));
        return new PhpClassTemplate(language.nextTemplateId(), "PDORow", false, false, false, false, false,
                null, List.of(), List.of(), props, List.of(), Map.of());
    }

    private static PhpClassTemplate pdoExceptionTemplate(PhpLanguage language) {
        List<PhpClassTemplate.PropertyDecl> props = List.of(
                new PhpClassTemplate.PropertyDecl("errorInfo", Visibility.PUBLIC, false, PhpNull.INSTANCE, null));
        return new PhpClassTemplate(language.nextTemplateId(), "PDOException", false, false, false, false, false,
                "RuntimeException", List.of(), List.of(), props, List.of(), Map.of());
    }

    private static PhpClassTemplate internalIteratorTemplate(PhpLanguage language) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        methods.put("current", method(language, "current", PdoFunctions::iteratorCurrent));
        methods.put("key", method(language, "key", PdoFunctions::iteratorKey));
        methods.put("next", method(language, "next", PdoFunctions::iteratorNext));
        methods.put("rewind", method(language, "rewind", PdoFunctions::iteratorRewind));
        methods.put("valid", method(language, "valid", PdoFunctions::iteratorValid));
        return new PhpClassTemplate(language.nextTemplateId(), "InternalIterator", false, false, false, true, false,
                null, List.of("Iterator"), List.of(), List.of(), List.of(), methods);
    }
}
