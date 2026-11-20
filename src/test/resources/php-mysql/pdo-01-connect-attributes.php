<?php
require __DIR__ . '/pdo_connect.php';

echo "== constants ==\n";
blitz_show('param', [PDO::PARAM_NULL, PDO::PARAM_INT, PDO::PARAM_STR, PDO::PARAM_LOB, PDO::PARAM_BOOL]);
blitz_show('fetch', [PDO::FETCH_DEFAULT, PDO::FETCH_LAZY, PDO::FETCH_ASSOC, PDO::FETCH_NUM, PDO::FETCH_BOTH,
    PDO::FETCH_OBJ, PDO::FETCH_BOUND, PDO::FETCH_COLUMN, PDO::FETCH_CLASS, PDO::FETCH_INTO, PDO::FETCH_NAMED,
    PDO::FETCH_KEY_PAIR, PDO::FETCH_GROUP, PDO::FETCH_UNIQUE, PDO::FETCH_PROPS_LATE]);
blitz_show('attr', [PDO::ATTR_AUTOCOMMIT, PDO::ATTR_TIMEOUT, PDO::ATTR_ERRMODE, PDO::ATTR_CASE,
    PDO::ATTR_ORACLE_NULLS, PDO::ATTR_PERSISTENT, PDO::ATTR_DRIVER_NAME, PDO::ATTR_STRINGIFY_FETCHES,
    PDO::ATTR_EMULATE_PREPARES, PDO::ATTR_DEFAULT_FETCH_MODE, PDO::ATTR_DEFAULT_STR_PARAM]);
blitz_show('err', [PDO::ERRMODE_SILENT, PDO::ERRMODE_WARNING, PDO::ERRMODE_EXCEPTION, PDO::ERR_NONE]);
blitz_show('case', [PDO::CASE_NATURAL, PDO::CASE_UPPER, PDO::CASE_LOWER]);
blitz_show('nulls', [PDO::NULL_NATURAL, PDO::NULL_EMPTY_STRING, PDO::NULL_TO_STRING]);
blitz_show('mysql', [PDO::MYSQL_ATTR_USE_BUFFERED_QUERY, PDO::MYSQL_ATTR_INIT_COMMAND,
    PDO::MYSQL_ATTR_FOUND_ROWS, PDO::MYSQL_ATTR_IGNORE_SPACE, PDO::MYSQL_ATTR_SSL_CA]);
blitz_show('actual-constants', [defined('PDO::ATTR_ERRMODE'), defined('PDO_ATTR_ERRMODE'), defined('MYSQL_ATTR_INIT_COMMAND')]);

echo "== classes ==\n";
$p = blitz_pdo();
blitz_show('pdo-class', get_class($p));
blitz_show('pdo-interfaces', class_implements($p));
blitz_show('stmt-class', get_class($p->prepare('SELECT 1')));
blitz_show('stmt-interfaces', array_keys(class_implements($p->prepare('SELECT 1'))));
blitz_show('drivers', PDO::getAvailableDrivers());
blitz_show('pdo-drivers', pdo_drivers());

echo "== attributes ==\n";
blitz_show('errmode', $p->getAttribute(PDO::ATTR_ERRMODE));
blitz_show('fetch-mode', $p->getAttribute(PDO::ATTR_DEFAULT_FETCH_MODE));
blitz_show('case', $p->getAttribute(PDO::ATTR_CASE));
blitz_show('nulls', $p->getAttribute(PDO::ATTR_ORACLE_NULLS));
blitz_show('stringify', $p->getAttribute(PDO::ATTR_STRINGIFY_FETCHES));
blitz_show('emulate', $p->getAttribute(PDO::ATTR_EMULATE_PREPARES));
blitz_show('persistent', $p->getAttribute(PDO::ATTR_PERSISTENT));
blitz_show('autocommit', $p->getAttribute(PDO::ATTR_AUTOCOMMIT));
blitz_show('driver', $p->getAttribute(PDO::ATTR_DRIVER_NAME));
blitz_show('buffered', $p->getAttribute(PDO::MYSQL_ATTR_USE_BUFFERED_QUERY));
blitz_show('str-param', $p->getAttribute(PDO::ATTR_DEFAULT_STR_PARAM));
blitz_show('stmt-class-attr', $p->getAttribute(PDO::ATTR_STATEMENT_CLASS));
blitz_show('server-version-string', is_string($p->getAttribute(PDO::ATTR_SERVER_VERSION))
    && $p->getAttribute(PDO::ATTR_SERVER_VERSION) !== '');
blitz_show('client-version-prefix', str_starts_with($p->getAttribute(PDO::ATTR_CLIENT_VERSION), 'mysqlnd '));
blitz_show('connection-status', str_ends_with($p->getAttribute(PDO::ATTR_CONNECTION_STATUS), 'via TCP/IP'));

blitz_show('set-errmode', $p->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_SILENT));
blitz_show('errmode-2', $p->getAttribute(PDO::ATTR_ERRMODE));
blitz_show('set-errmode-2', $p->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION));
blitz_show('set-case', $p->setAttribute(PDO::ATTR_CASE, PDO::CASE_UPPER));
blitz_show('case-2', $p->getAttribute(PDO::ATTR_CASE));
$p->setAttribute(PDO::ATTR_CASE, PDO::CASE_NATURAL);
blitz_show('set-nulls', $p->setAttribute(PDO::ATTR_ORACLE_NULLS, PDO::NULL_TO_STRING));
blitz_show('nulls-2', $p->getAttribute(PDO::ATTR_ORACLE_NULLS));
$p->setAttribute(PDO::ATTR_ORACLE_NULLS, PDO::NULL_NATURAL);
blitz_show('set-fetch', $p->setAttribute(PDO::ATTR_DEFAULT_FETCH_MODE, PDO::FETCH_ASSOC));
blitz_show('fetch-mode-2', $p->getAttribute(PDO::ATTR_DEFAULT_FETCH_MODE));
$p->setAttribute(PDO::ATTR_DEFAULT_FETCH_MODE, PDO::FETCH_BOTH);
blitz_show('set-stringify', $p->setAttribute(PDO::ATTR_STRINGIFY_FETCHES, true));
blitz_show('stringify-2', $p->getAttribute(PDO::ATTR_STRINGIFY_FETCHES));
$p->setAttribute(PDO::ATTR_STRINGIFY_FETCHES, false);
blitz_show('set-emulate', $p->setAttribute(PDO::ATTR_EMULATE_PREPARES, false));
blitz_show('emulate-2', $p->getAttribute(PDO::ATTR_EMULATE_PREPARES));
$p->setAttribute(PDO::ATTR_EMULATE_PREPARES, true);
blitz_show('set-buffered', $p->setAttribute(PDO::MYSQL_ATTR_USE_BUFFERED_QUERY, false));
blitz_show('buffered-2', $p->getAttribute(PDO::MYSQL_ATTR_USE_BUFFERED_QUERY));
$p->setAttribute(PDO::MYSQL_ATTR_USE_BUFFERED_QUERY, true);
blitz_show('set-unknown', $p->setAttribute(9999, 1));

echo "== options ==\n";
$q = blitz_pdo([PDO::ATTR_ERRMODE => PDO::ERRMODE_SILENT, PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_NUM,
    PDO::ATTR_EMULATE_PREPARES => false, PDO::ATTR_TIMEOUT => 5, PDO::ATTR_PERSISTENT => true,
    PDO::MYSQL_ATTR_INIT_COMMAND => 'SET @blitz_init = 7', PDO::MYSQL_ATTR_USE_BUFFERED_QUERY => false]);
blitz_show('option-errmode', $q->getAttribute(PDO::ATTR_ERRMODE));
blitz_show('option-fetch', $q->getAttribute(PDO::ATTR_DEFAULT_FETCH_MODE));
blitz_show('option-emulate', $q->getAttribute(PDO::ATTR_EMULATE_PREPARES));
blitz_show('option-buffered', $q->getAttribute(PDO::MYSQL_ATTR_USE_BUFFERED_QUERY));
blitz_show('option-init', $q->query('SELECT @blitz_init')->fetchColumn());
blitz_show('option-persistent', $q->getAttribute(PDO::ATTR_PERSISTENT));

echo "== quote ==\n";
blitz_show('plain', $p->quote('abc'));
blitz_show('quote', $p->quote("it's"));
blitz_show('backslash', $p->quote('a\\b'));
blitz_show('newline', $p->quote("a\nb"));
blitz_show('nul', $p->quote("a\0b"));
blitz_show('ctrl', $p->quote("a\x1ab"));
blitz_show('empty', $p->quote(''));
blitz_show('null', $p->quote(null));
blitz_show('int', $p->quote(42));
blitz_show('true', $p->quote(true));
blitz_show('false', $p->quote(false));
blitz_show('int-type', $p->quote('abc', PDO::PARAM_INT));

echo "== session ==\n";
blitz_show('charset', $p->query('SELECT @@character_set_client, @@character_set_connection, @@character_set_results')->fetch(PDO::FETCH_NUM));
$q = new PDO(blitz_pdo_dsn(';charset=latin1'), blitz_db_settings()[1], blitz_db_settings()[2]);
blitz_show('latin1', $q->query('SELECT @@character_set_client, @@character_set_connection, @@character_set_results')->fetch(PDO::FETCH_NUM));

echo "== connect errors ==\n";
$s = blitz_db_settings();
blitz_error('badpass', fn() => new PDO('mysql:host=127.0.0.1;port=' . $s[4], $s[1], 'wrong-password'));
blitz_error('nodb', fn() => new PDO('mysql:host=127.0.0.1;port=' . $s[4] . ';dbname=no_such_db_here', $s[1], $s[2]));
blitz_error('refused', fn() => new PDO('mysql:host=127.0.0.1;port=1', $s[1], $s[2]));
blitz_error('badhost', fn() => new PDO('mysql:host=no.such.host.invalid;port=' . $s[4], $s[1], $s[2]));
blitz_error('empty-dsn', fn() => new PDO('', $s[1], $s[2]));
blitz_error('no-colon', fn() => new PDO('mysql', $s[1], $s[2]));
blitz_error('unknown-driver', fn() => new PDO('pgsql:host=127.0.0.1', $s[1], $s[2]));
blitz_error('bad-charset', fn() => new PDO(blitz_pdo_dsn(';charset=nosuchcharset'), $s[1], $s[2]));
blitz_error('empty-charset', fn() => new PDO(blitz_pdo_dsn(';charset='), $s[1], $s[2]));
blitz_error('bad-socket', fn() => new PDO('mysql:unix_socket=/tmp/blitz-pdo-no-such.sock;dbname=' . $s[3], $s[1], $s[2]));
