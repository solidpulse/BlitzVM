<?php
require_once __DIR__ . '/boot.php';

echo 'server=', $_SERVER['BOOT_SERVER'] ?? 'missing', "\n";
echo 'env=', $_ENV['BOOT_SERVER'] ?? 'missing', "\n";
echo 'getenv=', var_export(getenv('BOOT_SERVER'), true), "\n";
echo 'server only=', $_SERVER['BOOT_ONLY_SERVER'] ?? 'missing', "\n";
echo 'request key=', $_SERVER['HTTP_X_REQUEST_KEY'] ?? 'missing', "\n";
echo 'request env=', $_ENV['HTTP_X_REQUEST_KEY'] ?? 'missing', "\n";
echo 'request getenv=', var_export(getenv('HTTP_X_REQUEST_KEY'), true), "\n";
