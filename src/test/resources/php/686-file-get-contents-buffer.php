<?php
// file_get_contents reads the whole file into a reused buffer: the contents must be exact for
// empty, small, larger-than-the-buffer and binary files, and for every window.
$base = sys_get_temp_dir() . '/php-fgc-buffer-' . getmypid();
if (!is_dir($base)) {
    mkdir($base);
}

$small = 'abc';
$binary = '';
for ($i = 0; $i < 256; $i++) {
    $binary .= chr($i);
}
$large = str_repeat("0123456789abcdef", 4096); // 64 KiB, past the 8 KiB starting buffer
file_put_contents("$base/empty", '');
file_put_contents("$base/small", $small);
file_put_contents("$base/binary", $binary);
file_put_contents("$base/large", $large);

echo 'empty|', json_encode(file_get_contents("$base/empty")), '|', strlen(file_get_contents("$base/empty")), "\n";
echo 'small|', file_get_contents("$base/small"), '|', file_get_contents("$base/small", false, null, 1), '|',
    file_get_contents("$base/small", false, null, 1, 1), '|', file_get_contents("$base/small", false, null, -2), "\n";
echo 'binary|', bin2hex(file_get_contents("$base/binary")), "\n";
echo 'large|', strlen(file_get_contents("$base/large")), '|', md5(file_get_contents("$base/large")), '|',
    file_get_contents("$base/large", false, null, 65530), '|', file_get_contents("$base/large", false, null, 8, 4), "\n";

// Repeated reads keep the buffer but must not leak the previous contents.
for ($i = 0; $i < 3; $i++) {
    echo 'repeat', $i, '|', file_get_contents("$base/small"), '|', strlen(file_get_contents("$base/empty")), "\n";
}

var_dump(@file_get_contents("$base/missing"));
var_dump(@file_get_contents("$base"));

foreach (glob("$base/*") as $file) {
    unlink($file);
}
rmdir($base);
