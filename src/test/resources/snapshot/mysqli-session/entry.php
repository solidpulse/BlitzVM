<?php
require_once __DIR__ . '/boot.php';

// A restored request may find the table already gone (the previous restore dropped it).
mysqli_query($link, 'CREATE TABLE IF NOT EXISTS snap_session (id INT AUTO_INCREMENT PRIMARY KEY, body VARCHAR(64)) ENGINE=InnoDB');
mysqli_query($link, 'DELETE FROM snap_session');

$empty = mysqli_query($link, 'SELECT id, body FROM snap_session');
echo 'rows=', mysqli_num_rows($empty), "\n";
mysqli_free_result($empty);

mysqli_query($link, "INSERT INTO snap_session (body) VALUES ('hello')");
echo 'insert_id=', mysqli_insert_id($link), "\n";
echo 'affected=', mysqli_affected_rows($link), "\n";
echo 'charset=', mysqli_character_set_name($link), "\n";

$mode = mysqli_query($link, 'SELECT @@SESSION.sql_mode');
echo 'mode=', mysqli_fetch_row($mode)[0], "\n";
mysqli_free_result($mode);

$db = mysqli_query($link, 'SELECT DATABASE()');
echo 'db=', mysqli_fetch_row($db)[0], "\n";
mysqli_free_result($db);

$row = mysqli_query($link, 'SELECT id, body FROM snap_session');
echo 'body=', implode(',', mysqli_fetch_row($row)), "\n";
mysqli_free_result($row);

mysqli_query($link, 'DROP TABLE IF EXISTS snap_session');
