<?php
$s = "é😀\0\xFF\nnext\xE9\r\n";
$f = tempnam(sys_get_temp_dir(), 'bytes');
var_dump(file_put_contents($f, $s), file_get_contents($f) === $s);
echo bin2hex(file_get_contents($f, false, null, -4)), "\n";
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
ob_start();
$n = file_put_contents('php://output', $s);
$out = ob_get_clean();
var_dump($n, $out === $s);
var_dump(md5_file($f) === md5($s), md5_file($f, true) === md5($s, true));
var_dump(hash_file('sha256', $f, true) === hash('sha256', $s, true),
         hash_hmac_file('sha256', $f, "é\xFF", true) === hash_hmac('sha256', $s, "é\xFF", true));
$ctx = hash_init('sha256');
hash_update_file($ctx, $f);
var_dump(hash_final($ctx) === hash('sha256', $s));
$ctx = hash_init('sha256');
$h = fopen($f, 'rb');
var_dump(hash_update_stream($ctx, $h), hash_final($ctx, true) === hash('sha256', $s, true));
fclose($h);
unlink($f);
foreach (['gzcompress', 'gzdeflate', 'gzencode'] as $fn) {
    $z = $fn($s);
    var_dump(zlib_decode($z) === $s);
}
