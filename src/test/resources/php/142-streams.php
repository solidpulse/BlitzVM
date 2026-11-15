<?php
$dir = sys_get_temp_dir() . '/blitz-142-streams';
// Start from an empty directory; scandir always runs so resource ids do not depend on leftovers.
if (file_exists("$dir/sub/inner.php")) { unlink("$dir/sub/inner.php"); }
if (is_dir("$dir/sub")) { rmdir("$dir/sub"); }
if (!is_dir($dir)) { mkdir($dir); }
foreach (scandir($dir) as $n) { if ($n !== '.' && $n !== '..') unlink("$dir/$n"); }
$p = "$dir/a.txt";
function bn($list) { $out = []; foreach ($list as $x) { $out[] = basename($x); } return $out; }

// resource basics and ids
$f = fopen($p, 'w');
var_dump($f, is_resource($f), gettype($f), get_resource_type($f), (int)$f === get_resource_id($f), get_debug_type($f));
var_dump(fwrite($f, "line1\nline2\n"), fputs($f, "line3"), ftell($f));
var_dump(fclose($f), is_resource($f), gettype($f), get_resource_type($f), get_debug_type($f));
echo "$f\n";

// reading
$f = fopen($p, 'r');
var_dump(fgets($f), fgets($f), feof($f), fgets($f), feof($f), fgets($f));
rewind($f);
var_dump(fread($f, 3), ftell($f), fgetc($f), fseek($f, -2, SEEK_END), fread($f, 10), feof($f));
var_dump(fseek($f, 0), feof($f), stream_get_contents($f, 5), stream_get_contents($f, -1, 12));
var_dump(stream_get_meta_data($f)['mode'], stream_get_meta_data($f)['wrapper_type'], stream_get_meta_data($f)['seekable']);
fclose($f);
$f = fopen($p, 'r');
var_dump(fgets($f, 4));
$lines = [];
while (($line = fgets($f)) !== false) { $lines[] = rtrim($line); }
var_dump($lines);
fclose($f);

// modes
$f = fopen($p, 'a'); var_dump(ftell($f)); fwrite($f, "\nline4"); var_dump(ftell($f)); fclose($f);
$f = fopen($p, 'r+'); fwrite($f, "LINE"); fclose($f);
var_dump(file_get_contents($p));
$f = fopen($p, 'c+'); var_dump(fread($f, 4)); ftruncate($f, 4); fclose($f);
var_dump(file_get_contents($p), filesize($p));
var_dump(file_exists("$dir/missing.txt"));
$x = fopen("$dir/x.txt", 'x'); fwrite($x, "x"); fclose($x);

// file(), readfile, md5_file
file_put_contents($p, "a\n\nb\r\nc");
var_dump(file($p), file($p, FILE_IGNORE_NEW_LINES), file($p, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES));
var_dump(readfile($p));
echo "\n";
var_dump(md5_file($p), sha1_file($p), md5_file($p) === md5(file_get_contents($p)));

// memory and temp streams
$m = fopen('php://memory', 'w+');
fwrite($m, "hello\nworld");
var_dump(ftell($m), feof($m));
rewind($m);
var_dump(fgets($m), stream_get_contents($m), feof($m), fstat($m)['size']);
var_dump(stream_get_meta_data($m));
fseek($m, 20); fwrite($m, "!"); rewind($m); var_dump(strlen(stream_get_contents($m)));
$t = fopen('php://temp', 'r+'); fwrite($t, "abcdef"); ftruncate($t, 3); rewind($t); var_dump(stream_get_contents($t));
$o = fopen('php://output', 'w'); fwrite($o, "to output\n"); fclose($o);
ob_start(); $o = fopen('php://output', 'w'); fwrite($o, "captured"); fclose($o); var_dump(ob_get_clean());

// stat family
$st = stat($p);
var_dump(count($st), $st['size'], $st[7], $st['size'] === filesize($p), is_int($st['mtime']), ($st['mode'] & 61440) === 32768);
var_dump(fstat(fopen($p, 'r'))['size'], is_link($p), lstat($p)['size']);
chmod($p, 416); // 0640 clearstatcache(); var_dump(sprintf('%o', fileperms($p) & 0777));
chmod($p, 420); // 0644 clearstatcache(); var_dump(sprintf('%o', fileperms($p) & 0777));
var_dump(touch("$dir/t.txt", 1700000000), filemtime("$dir/t.txt"), file_exists("$dir/t.txt"));
var_dump(copy($p, "$dir/copy.txt"), file_get_contents("$dir/copy.txt") === file_get_contents($p));
var_dump(rename("$dir/copy.txt", "$dir/moved.txt"), file_exists("$dir/copy.txt"), file_exists("$dir/moved.txt"));
$tmp = tempnam($dir, 'pre'); var_dump(dirname($tmp) === realpath($dir), str_starts_with(basename($tmp), 'pre'), strlen(basename($tmp)), file_exists($tmp)); unlink($tmp);
$tf = tmpfile(); fwrite($tf, "temp"); fseek($tf, 0); var_dump(fread($tf, 10)); fclose($tf);
var_dump(is_float(disk_free_space($dir)), disk_free_space($dir) > 0);

// directories
mkdir("$dir/sub");
file_put_contents("$dir/sub/inner.php", "<?php");
$d = opendir($dir);
$names = [];
while (($n = readdir($d)) !== false) { $names[] = $n; }
sort($names);
var_dump($names);
rewinddir($d);
var_dump(readdir($d) !== false);
closedir($d);

// glob
var_dump(bn(glob("$dir/*.txt")));
var_dump(bn(glob("$dir/*")));
var_dump(bn(glob("$dir/*", GLOB_ONLYDIR)));
var_dump(bn(glob("$dir/{a,t}.txt", GLOB_BRACE)));
var_dump(bn(glob("$dir/[am]*.txt")));
var_dump(bn(glob("$dir/*/*.php")));
var_dump(glob("$dir/nothing*"), glob("$dir/nothing*", GLOB_NOCHECK) === ["$dir/nothing*"]);
var_dump(substr(glob("$dir/su*", GLOB_MARK)[0], -4));

// stream contexts
$c = stream_context_create(['http' => ['method' => 'POST', 'timeout' => 5]]);
var_dump(get_resource_type($c), stream_context_get_options($c));
stream_context_set_option($c, 'http', 'header', 'X-A: b');
var_dump(stream_context_get_options($c)['http']['header']);
var_dump(in_array('php', stream_get_wrappers()), in_array('file', stream_get_wrappers()));
var_dump(stream_is_local($p), stream_is_local('http://example.com/'));

// clean up
foreach (glob("$dir/sub/*") as $n) unlink($n);
rmdir("$dir/sub");
foreach (scandir($dir) as $n) { if ($n !== '.' && $n !== '..') unlink("$dir/$n"); }
rmdir($dir);
var_dump(is_dir($dir));
