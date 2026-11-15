<?php
// The same calls, in the same order, that wpdb::db_connect(), set_charset(), set_sql_mode(),
// query() and load_col_info() make.
require __DIR__ . '/inc_connect.php';
$s = blitz_db_settings();
mysqli_report(MYSQLI_REPORT_OFF);
$dbh = mysqli_init();
$client_flags = defined('MYSQL_CLIENT_FLAGS') ? MYSQL_CLIENT_FLAGS : 0;
@mysqli_real_connect($dbh, $s[0], $s[1], $s[2], null, $s[4], null, $client_flags);
if ($dbh->connect_errno) {
    echo "connect failed\n";
    exit;
}
var_dump($dbh instanceof mysqli, function_exists('mysqli_set_charset'));
var_dump(mysqli_set_charset($dbh, 'utf8mb4'));
mysqli_query($dbh, "SET NAMES 'utf8mb4' COLLATE 'utf8mb4_unicode_ci'");
var_dump(mysqli_select_db($dbh, $s[3]));
$res = mysqli_query($dbh, 'SELECT @@SESSION.sql_mode');
$modes_array = mysqli_fetch_array($res);
var_dump(is_string($modes_array[0]), isset($modes_array['@@SESSION.sql_mode']));
$incompatible = ['NO_ZERO_DATE', 'ONLY_FULL_GROUP_BY', 'STRICT_TRANS_TABLES', 'STRICT_ALL_TABLES', 'TRADITIONAL', 'ANSI'];
$modes = [];
foreach (explode(',', $modes_array[0]) as $m) {
    if (!in_array(strtoupper($m), $incompatible, true)) {
        $modes[] = $m;
    }
}
var_dump(mysqli_query($dbh, "SET SESSION sql_mode='" . implode(',', $modes) . "'"));

mysqli_query($dbh, "DROP TABLE IF EXISTS wp_options");
mysqli_query($dbh, "CREATE TABLE wp_options (option_id bigint(20) unsigned NOT NULL AUTO_INCREMENT, option_name varchar(191) NOT NULL DEFAULT '', option_value longtext NOT NULL, autoload varchar(20) NOT NULL DEFAULT 'yes', PRIMARY KEY (option_id), UNIQUE KEY option_name (option_name)) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
$opts = ['siteurl' => 'http://example.test', 'blogname' => 'Blitz', 'active_plugins' => serialize([]), 'date_format' => 'F j, Y'];
foreach ($opts as $k => $v) {
    $sql = sprintf("INSERT INTO `wp_options` (`option_name`, `option_value`, `autoload`) VALUES ('%s', '%s', '%s')",
        mysqli_real_escape_string($dbh, $k), mysqli_real_escape_string($dbh, $v), 'yes');
    if (mysqli_query($dbh, $sql)) {
        echo "insert ", mysqli_insert_id($dbh), " rows ", mysqli_affected_rows($dbh), "\n";
    }
}
$result = mysqli_query($dbh, "SELECT option_name, option_value FROM wp_options WHERE autoload IN ( 'yes', 'on', 'auto-on', 'auto' )");
$last_result = [];
$num_rows = 0;
if ($result instanceof mysqli_result) {
    while ($row = mysqli_fetch_object($result)) {
        $last_result[$num_rows] = $row;
        $num_rows++;
    }
}
$rows_affected = mysqli_affected_rows($dbh);
var_dump($num_rows, $rows_affected);
foreach ($last_result as $row) {
    echo $row->option_name, ' => ', $row->option_value, "\n";
}
$col_info = [];
$num_fields = mysqli_num_fields($result);
for ($i = 0; $i < $num_fields; $i++) {
    $col_info[$i] = mysqli_fetch_field($result);
}
echo $col_info[0]->name, ' ', $col_info[0]->table, ' ', $col_info[1]->type, "\n";
if ($result instanceof mysqli_result) {
    mysqli_free_result($result);
}
while (mysqli_more_results($dbh)) {
    mysqli_next_result($dbh);
}
var_dump(mysqli_query($dbh, 'DO 1') !== false);
var_dump(mysqli_character_set_name($dbh), is_string(mysqli_get_server_info($dbh)));
var_dump(mysqli_close($dbh));
