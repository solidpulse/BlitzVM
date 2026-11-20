<?php
require_once __DIR__ . '/../inc/mysqli.php';
$link = snap_mysqli_link();
$result = mysqli_query($link, 'SELECT 1');
// The row was never read, so the result is live consumption state.
