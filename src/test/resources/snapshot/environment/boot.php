<?php
// What a boot file's dotenv loader does: all three views of a variable, plus one that
// only the server array gets, and a key a request can also supply (HTTP_X_REQUEST_KEY).
$_SERVER['BOOT_SERVER'] = 'server';
$_ENV['BOOT_SERVER'] = 'server';
putenv('BOOT_SERVER=server');

$_SERVER['BOOT_ONLY_SERVER'] = 'server-only';

$_SERVER['HTTP_X_REQUEST_KEY'] = 'boot';
$_ENV['HTTP_X_REQUEST_KEY'] = 'boot';
putenv('HTTP_X_REQUEST_KEY=boot');
