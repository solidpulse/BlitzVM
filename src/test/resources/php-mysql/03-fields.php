<?php
require __DIR__ . '/inc_connect.php';
$db = blitz_connect();
mysqli_query($db, "DROP TABLE IF EXISTS t_fields");
mysqli_query($db, "CREATE TABLE t_fields (
  ID bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  post_title text NOT NULL,
  post_name varchar(200) NOT NULL DEFAULT '',
  post_date datetime NOT NULL DEFAULT '0000-00-00 00:00:00',
  menu_order int(11) NOT NULL DEFAULT 0,
  price decimal(8,2) DEFAULT NULL,
  raw varbinary(16) DEFAULT NULL,
  blobby longblob,
  PRIMARY KEY (ID),
  KEY post_name (post_name(191)),
  UNIQUE KEY menu_order (menu_order)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
mysqli_query($db, "INSERT INTO t_fields (post_title, post_name, menu_order, price, raw) VALUES ('Hello', 'hello', 1, 9.99, 'abc')");
$r = mysqli_query($db, "SELECT f.*, f.ID AS alias_id, 1+1 AS expr, NULL AS nothing FROM t_fields f");
$n = mysqli_num_fields($r);
echo "num_fields: $n\n";
for ($i = 0; $i < $n; $i++) {
    $f = mysqli_fetch_field($r);
    foreach (['name', 'orgname', 'table', 'orgtable', 'def', 'db', 'catalog', 'max_length', 'length', 'charsetnr', 'flags', 'type', 'decimals'] as $p) {
        echo $p, '=', var_export($f->$p, true), ' ';
    }
    echo "\n";
}
var_dump(mysqli_fetch_field($r));
var_dump(get_class($r), get_class($db), $r instanceof mysqli_result, $db instanceof mysqli);
