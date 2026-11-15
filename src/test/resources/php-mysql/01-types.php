<?php
require __DIR__ . '/inc_connect.php';
$db = blitz_connect();
mysqli_query($db, "DROP TABLE IF EXISTS t_types");
var_dump(mysqli_query($db, "CREATE TABLE t_types (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  i INT, ti TINYINT(1), si SMALLINT, d DECIMAL(10,3), f FLOAT, dbl DOUBLE,
  vc VARCHAR(20) NOT NULL DEFAULT '', txt LONGTEXT, dt DATETIME NOT NULL DEFAULT '0000-00-00 00:00:00',
  da DATE, tm TIME, ts TIMESTAMP NULL, y YEAR, b BLOB, e ENUM('a','b'), bi BIT(8), js JSON
) DEFAULT CHARSET=utf8mb4"));
var_dump(mysqli_query($db, "INSERT INTO t_types (i, ti, si, d, f, dbl, vc, txt, dt, da, tm, ts, y, b, e, bi, js) VALUES
  (-5, 1, 300, 12.5, 1.1, 0.1, 'héllo', 'long ✓ text', '2024-02-29 13:45:01', '2024-02-29', '-12:30:00', '2024-01-01 00:00:00', 2024, 'bytes', 'b', b'101', '{\"a\": 1}')"));
var_dump(mysqli_insert_id($db), mysqli_affected_rows($db));
var_dump(mysqli_query($db, "INSERT INTO t_types (vc) VALUES ('x'), ('y')"));
var_dump(mysqli_insert_id($db), mysqli_affected_rows($db));
$r = mysqli_query($db, "SELECT * FROM t_types ORDER BY id");
var_dump(mysqli_num_rows($r), mysqli_num_fields($r), mysqli_affected_rows($db), mysqli_insert_id($db));
while ($row = mysqli_fetch_assoc($r)) {
    var_dump($row);
}
mysqli_free_result($r);
