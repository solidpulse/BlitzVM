<?php
require_once __DIR__ . '/../inc/mysqli.php';
$link = snap_mysqli_link();
mysqli_select_db($link, 'blitz');
$probe = mysqli_query($link, 'SELECT 1');
mysqli_free_result($probe);
