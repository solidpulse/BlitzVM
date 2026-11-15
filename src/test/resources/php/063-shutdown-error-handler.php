<?php

$log = new stdClass();
$log->events = [];

$prev = set_error_handler(function ($errno, $errstr, $errfile, $errline) use ($log) {
    $hasMsg = str_contains($errstr, 'Failed') ? 'matched' : $errstr;
    $log->events[] = $errno . ':' . $hasMsg . ':' . (is_string($errfile) ? 'str' : 'no') . ':' . ($errline > 0 ? 'line' : 'noline');
    return true;
});
var_dump($prev === null);

@include 'inc/does_not_exist_phase_b.php'; // emits E_WARNING handled silently with source line
restore_error_handler();

register_shutdown_function(function ($tag) use ($log) {
    echo 'shutdown:' . $tag . '|events=' . implode(';', $log->events), "\n";
    register_shutdown_function(function () {
        echo "nested_shutdown\n";
    });
}, 'first');

echo "before_exit\n";
exit;
echo "never_printed\n";
