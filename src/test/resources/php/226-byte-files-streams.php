<?php
$s = "é😀\0\xFF\nnext\xE9\r\n";
$f = tempnam(sys_get_temp_dir(), 'bytes');
var_dump(file_put_contents($f, $s), file_get_contents($f) === $s);
echo bin2hex(file_get_contents($f, false, null, 1, 5)), "\n";
$h = fopen($f, 'rb');
echo bin2hex(fread($h, 2)), ':', bin2hex(fgets($h)), ':', bin2hex(fgets($h)), "\n";
fclose($h);
foreach (file($f) as $line) { echo bin2hex($line), "\n"; }
ob_start();
$n = readfile($f);
$read = ob_get_clean();
var_dump($n, $read === $s);
foreach (['php://memory', 'php://temp'] as $path) {
    $h = fopen($path, 'w+b');
    var_dump(fwrite($h, $s), ftell($h));
    rewind($h);
    var_dump(stream_get_contents($h) === $s);
    rewind($h);
    echo bin2hex(fgetc($h)), "\n";
    fclose($h);
}
ob_start();
$h = fopen('php://output', 'wb');
fputs($h, $s);
fclose($h);
var_dump(ob_get_clean() === $s);
var_dump(md5_file($f) === md5($s), md5_file($f, true) === md5($s, true));
unlink($f);
foreach (['gzcompress', 'gzdeflate', 'gzencode'] as $fn) {
    $z = $fn($s);
    var_dump(zlib_decode($z) === $s);
}
