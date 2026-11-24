<?php
// Output buffering levels: nested buffers, contents and length, clean/flush at both ends,
// and a handler that echoes and opens another buffer while a level is being read.
echo "top\n";

ob_start();
echo 'a';
ob_start();
echo 'b';
echo 'level=', ob_get_level(), ' inner=', ob_get_length(), "\n";
$inner = ob_get_clean();
echo 'inner=', $inner, ' level=', ob_get_level(), "\n";
echo 'c';
ob_start();
echo 'second inner';
echo ' second=', ob_get_length(), "\n";
$clean = ob_get_clean();
echo 'clean=', $clean, "\n";
$outer = ob_get_clean();
echo 'outer=', $outer, "\n";

ob_start();
echo 'kept';
echo ' contents=', ob_get_contents(), ' length=', ob_get_length(), "\n";
ob_clean();
echo 'after-clean=', ob_get_contents(), "\n";
echo 'kept two';
$flushed = ob_get_flush();
echo 'flushed=', $flushed, "\n";

ob_start();
echo 'gone';
ob_end_clean();
echo "after-end-clean\n";

ob_start();
echo 'end flush';
ob_end_flush();
echo "\n";

ob_start(function ($text, $phase) {
    return strtoupper($text) . '!' . $phase;
});
echo 'h';
ob_end_flush();
echo "\n";

ob_start(function ($text) {
    return strrev($text);
});
echo 'drawer';
echo ' get=', ob_get_flush(), "\n";

// A buffer popped and restarted must start empty, even though its builder is reused.
ob_start();
echo 'one';
ob_end_flush();
ob_start();
echo 'two level=', ob_get_level(), "\n";
echo 'fresh=', ob_get_clean();
echo "\n";
