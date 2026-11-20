<?php
require_once __DIR__ . '/boot.php';
echo 'mysqli=', $link instanceof mysqli ? 'yes' : 'no', "\n";
echo 'errno=', mysqli_errno($link), "\n";
echo 'error=', var_export(mysqli_error($link), true), "\n";
echo 'server_info=', var_export(mysqli_get_server_info($link), true), "\n";
echo 'close=', var_export(mysqli_close($link), true), "\n";
