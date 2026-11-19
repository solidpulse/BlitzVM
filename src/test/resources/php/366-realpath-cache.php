<?php
// realpath() results are cached; removing or renaming a path must not leave a stale answer.
$dir = sys_get_temp_dir() . '/php-realpath-' . getmypid();
mkdir($dir);
$a = $dir . '/a.txt';
file_put_contents($a, 'x');
var_dump(realpath($a) === $a, realpath($a) === $a);
unlink($a);
var_dump(realpath($a));
file_put_contents($a, 'y');
var_dump(realpath($a) === $a);
rename($a, $dir . '/b.txt');
var_dump(realpath($a), realpath($dir . '/b.txt') === $dir . '/b.txt');
var_dump(realpath($dir . '/sub/../b.txt'));
mkdir($dir . '/sub');
var_dump(realpath($dir . '/sub/../b.txt') === $dir . '/b.txt');
rmdir($dir . '/sub');
var_dump(realpath($dir . '/sub'));
clearstatcache(true);
unlink($dir . '/b.txt');
rmdir($dir);
var_dump(realpath($dir));
