<?php
require_once __DIR__ . '/../inc/mysqli.php';
$link = snap_mysqli_link();
$stmt = mysqli_prepare($link, 'SELECT 1');
