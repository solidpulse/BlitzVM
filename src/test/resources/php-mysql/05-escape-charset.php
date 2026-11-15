<?php
require __DIR__ . '/inc_connect.php';
$db = blitz_connect();
var_dump(mysqli_character_set_name($db));
var_dump(mysqli_set_charset($db, 'utf8mb4'), mysqli_character_set_name($db));
$tricky = "it's a \"quote\" \\ back\nline\rret\x1aend" . chr(0) . "nul";
$e = mysqli_real_escape_string($db, $tricky);
var_dump($e);
mysqli_query($db, "DROP TABLE IF EXISTS t_esc");
mysqli_query($db, "CREATE TABLE t_esc (id INT AUTO_INCREMENT PRIMARY KEY, v TEXT) DEFAULT CHARSET=utf8mb4");
var_dump(mysqli_query($db, "INSERT INTO t_esc (v) VALUES ('$e')"));
$emoji = mysqli_real_escape_string($db, "emoji 😀 and ü and 漢字");
var_dump(mysqli_query($db, "INSERT INTO t_esc (v) VALUES ('$emoji')"));
$r = mysqli_query($db, "SELECT v, LENGTH(v) AS bytes, CHAR_LENGTH(v) AS chars FROM t_esc ORDER BY id");
$row = mysqli_fetch_assoc($r);
var_dump($row['v'] === $tricky, $row['bytes'], $row['chars']);
var_dump(mysqli_fetch_assoc($r));
var_dump(mysqli_real_escape_string($db, ""), mysqli_real_escape_string($db, "plain"));
