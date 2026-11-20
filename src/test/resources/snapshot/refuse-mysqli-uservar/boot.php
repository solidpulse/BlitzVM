<?php
require_once __DIR__ . '/../inc/mysqli.php';
$link = snap_mysqli_link();
mysqli_query($link, 'SET @snap_x = 1');
