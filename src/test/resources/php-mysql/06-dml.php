<?php
require __DIR__ . '/inc_connect.php';
$db = blitz_connect();
mysqli_query($db, "DROP TABLE IF EXISTS t_dml");
var_dump(mysqli_query($db, "CREATE TABLE t_dml (id INT AUTO_INCREMENT PRIMARY KEY, n INT, s VARCHAR(10))"));
var_dump(mysqli_affected_rows($db), mysqli_insert_id($db));
var_dump(mysqli_query($db, "INSERT INTO t_dml (n, s) VALUES (1, 'a'), (2, 'b'), (3, 'c')"));
var_dump(mysqli_affected_rows($db), mysqli_insert_id($db));
var_dump(mysqli_query($db, "UPDATE t_dml SET s = 'z' WHERE n >= 2"));
var_dump(mysqli_affected_rows($db), mysqli_insert_id($db));
// Rows that already have the value are matched but not changed
var_dump(mysqli_query($db, "UPDATE t_dml SET s = 'z' WHERE n >= 1"));
var_dump(mysqli_affected_rows($db));
var_dump(mysqli_query($db, "DELETE FROM t_dml WHERE n = 3"));
var_dump(mysqli_affected_rows($db));
var_dump(mysqli_query($db, "INSERT INTO t_dml (id, n) VALUES (100, 9)"));
var_dump(mysqli_insert_id($db));
var_dump(mysqli_query($db, "INSERT INTO t_dml (n) VALUES (10)"));
var_dump(mysqli_insert_id($db), $db->insert_id, $db->affected_rows);
var_dump(mysqli_query($db, "REPLACE INTO t_dml (id, n) VALUES (100, 11)"));
var_dump(mysqli_affected_rows($db));
var_dump(mysqli_query($db, "INSERT INTO t_dml (id, n) VALUES (100, 12) ON DUPLICATE KEY UPDATE n = 12"));
var_dump(mysqli_affected_rows($db));
$r = mysqli_query($db, "SELECT COUNT(*) AS c, SUM(n) AS total FROM t_dml");
var_dump(mysqli_fetch_assoc($r), mysqli_affected_rows($db), mysqli_field_count($db));
var_dump(mysqli_query($db, "SET @x = 5"), mysqli_affected_rows($db));
var_dump(mysqli_query($db, "SELECT LAST_INSERT_ID() AS id") instanceof mysqli_result);
