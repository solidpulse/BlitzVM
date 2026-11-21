<?php
$messages = [];
$results = [];
set_error_handler(function ($no, $message) use (&$messages) { $messages[] = $no . ':' . $message; return true; });
$results[] = header("X-CR: a\rb");
$results[] = header("X-LF: a\nb");
$results[] = header("X-NUL: a\0b");
$results[] = header("X-Na\0me: v");
$results[] = header("X-Na\rme: v");
$results[] = header("X-Trailing: v\r\n");
$results[] = header("HTTP/1.1 201 Created\0");
$results[] = header("X-Status: ok", true, 404);
$results[] = setcookie("nul\0name", "v");
$results[] = setrawcookie("raw", "a\0b");
$results[] = setcookie("same", "v", ["samesite" => "La\rx"]);
foreach ($results as $r) { echo var_export($r, true), "\n"; }
echo count($messages), "\n";
foreach ($messages as $m) { echo $m, "\n"; }
