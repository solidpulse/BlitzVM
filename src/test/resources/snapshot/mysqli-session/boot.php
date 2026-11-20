<?php
require_once __DIR__ . '/../inc/mysqli.php';
$link = snap_mysqli_link();
mysqli_set_charset($link, 'utf8mb4');
mysqli_query($link, 'SET NAMES utf8mb4 COLLATE utf8mb4_unicode_520_ci');
mysqli_query($link, "SET SESSION sql_mode='STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION'");
mysqli_select_db($link, 'blitz');
mysqli_query($link, 'DROP TABLE IF EXISTS snap_session');
mysqli_query($link, 'CREATE TABLE snap_session (id INT AUTO_INCREMENT PRIMARY KEY, body VARCHAR(64)) ENGINE=InnoDB');
$probe = mysqli_query($link, 'SELECT 1');
mysqli_free_result($probe);
