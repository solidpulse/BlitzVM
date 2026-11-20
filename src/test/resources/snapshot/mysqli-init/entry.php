<?php
require_once __DIR__ . '/boot.php';
echo 'mysqli=', $link instanceof mysqli ? 'yes' : 'no', "\n";
echo 'close=', var_export(mysqli_close($link), true), "\n";
