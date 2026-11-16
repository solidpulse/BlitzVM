<?php
$path = tempnam(sys_get_temp_dir(), "sel");
file_put_contents($path, "abc");
$h = fopen($path, "r");
$r = [$h];
$w = null;
$e = null;
var_dump(stream_select($r, $w, $e, 0, 0), $r === [$h]);
$r = [$h];
$w = [];
$e = [];
var_dump(stream_select($r, $w, $e, 1, 0));
$r = [];
$w = [$h];
$e = [];
var_dump(stream_select($r, $w, $e, 0, 0), $w === [$h]);
$r = [];
$w = [];
$e = [];
try {
    stream_select($r, $w, $e, 0, 0);
} catch (Throwable $x) {
    echo get_class($x), ": ", $x->getMessage(), "\n";
}
$m = fopen("php://memory", "r+");
$r = [$m];
$w = [];
$e = [];
try {
    stream_select($r, $w, $e, 0, 0);
} catch (Throwable $x) {
    echo get_class($x), ": ", $x->getMessage(), "\n";
}
var_dump(count($r));
try {
    stream_socket_enable_crypto($h, true);
} catch (Throwable $x) {
    echo get_class($x), ": ", $x->getMessage(), "\n";
}
var_dump(stream_socket_enable_crypto($h, true, STREAM_CRYPTO_METHOD_TLS_CLIENT));
var_dump(stream_socket_enable_crypto($h, false));
fclose($h);
fclose($m);
unlink($path);
