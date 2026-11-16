<?php
require __DIR__ . '/inc_connect.php';
$db = blitz_connect();
mysqli_set_charset($db, 'utf8mb4');
mysqli_query($db, 'CREATE TEMPORARY TABLE t_bytes (id INT PRIMARY KEY, `clé` TEXT, b BLOB) DEFAULT CHARSET=utf8mb4');
$text = 'café ’ 😀';
$binary = random_bytes(64);
// Force invalid UTF-8 and SQL metacharacters regardless of the random sample.
$binary[0] = "\xFF";
$binary[1] = "\0";
$binary[2] = "'";
$binary[3] = "\\";
$escapedText = mysqli_real_escape_string($db, $text);
$escapedBinary = mysqli_real_escape_string($db, $binary);
var_dump(mysqli_query($db, "INSERT INTO t_bytes VALUES (1, '$escapedText', '$escapedBinary')"));
$r = mysqli_query($db, 'SELECT `clé`, b, LENGTH(`clé`) AS text_bytes, LENGTH(b) AS blob_bytes FROM t_bytes');
$row = mysqli_fetch_assoc($r);
var_dump($row['clé'] === $text, $row['b'] === $binary, $row['text_bytes'], $row['blob_bytes']);
echo bin2hex($row['clé']), "\n";
var_dump(mysqli_fetch_field_direct($r, 0)->name === 'clé');
$all = '';
for ($i = 0; $i < 256; $i++) { $all .= chr($i); }
$escaped = mysqli_real_escape_string($db, $all);
mysqli_query($db, "INSERT INTO t_bytes VALUES (2, '😀', '$escaped')");
$r = mysqli_query($db, 'SELECT `clé`, b FROM t_bytes WHERE id = 2');
$row = mysqli_fetch_assoc($r);
var_dump($row['clé'] === '😀', $row['b'] === $all, strlen($row['b']));
mysqli_close($db);
