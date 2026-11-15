<?php
require __DIR__ . '/inc_connect.php';
$db = blitz_connect();
mysqli_query($db, "DROP TABLE IF EXISTS t_fetch");
mysqli_query($db, "CREATE TABLE t_fetch (id INT PRIMARY KEY, name VARCHAR(50), note TEXT) DEFAULT CHARSET=utf8mb4");
mysqli_query($db, "INSERT INTO t_fetch VALUES (1, 'alpha', NULL), (2, 'béta ☃', 'two'), (3, '', '0')");
$r = mysqli_query($db, "SELECT id, name, note FROM t_fetch ORDER BY id");
var_dump(mysqli_fetch_array($r));
var_dump(mysqli_fetch_array($r, MYSQLI_ASSOC));
var_dump(mysqli_fetch_array($r, MYSQLI_NUM));
var_dump(mysqli_fetch_array($r));
var_dump(mysqli_data_seek($r, 0), mysqli_fetch_row($r));
var_dump(mysqli_data_seek($r, 1));
$o = mysqli_fetch_object($r);
var_dump(get_class($o), $o->id, $o->name, $o->note);
var_dump(mysqli_data_seek($r, 9));
mysqli_free_result($r);

// Duplicate column names: the later column wins the associative key
$r = mysqli_query($db, "SELECT 1 AS x, 2 AS y, 3 AS x");
var_dump(mysqli_fetch_array($r, MYSQLI_BOTH));
mysqli_free_result($r);

// fetch_all, num_rows on an empty result
$r = mysqli_query($db, "SELECT id FROM t_fetch WHERE id > 1 ORDER BY id");
var_dump(mysqli_fetch_all($r), mysqli_fetch_all($r, MYSQLI_ASSOC));
$r = mysqli_query($db, "SELECT id FROM t_fetch WHERE id > 100");
var_dump(mysqli_num_rows($r), mysqli_fetch_assoc($r), mysqli_fetch_object($r));

// SHOW and DESCRIBE return results too
$r = mysqli_query($db, "SHOW TABLES LIKE 't_fetch'");
var_dump(mysqli_fetch_row($r));
$r = mysqli_query($db, "DESCRIBE t_fetch");
var_dump(mysqli_num_rows($r));
$row = mysqli_fetch_assoc($r);
var_dump($row['Field'], $row['Type'], $row['Key']);
