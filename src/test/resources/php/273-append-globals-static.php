<?php
$output = ''; $output .= 'top'; $copy = $output;
function global_append() { global $output; $output .= '-global'; }
global_append(); echo $output, '|', $copy, "\n";
function global_capture() {
    global $output;
    $f = function () use (&$output) { $output .= '-capture'; };
    $f(); return $output;
}
echo global_capture(), "\n";
function static_append() { static $s = ''; $s .= 'a'; return $s; }
echo static_append(), '|', static_append(), '|', static_append(), "\n";
$saved = $GLOBALS['output']; $output .= '!'; echo $saved, '|', $output, "\n";
function untouched_global() { $plain = ''; $plain .= 'plain'; return $plain; }
echo untouched_global(), "\n";
