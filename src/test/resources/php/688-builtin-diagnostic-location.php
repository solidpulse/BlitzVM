<?php
set_error_handler(function ($no, $str, $file, $line) {
    echo "handler: $no $str in ", basename($file), " on line $line\n";
    return true;
});
var_dump(is_a('stdClass', 'stdClass'));
function f() {
    return is_a('X', 'stdClass');
}
var_dump(f());
require __DIR__ . '/inc/688-diag-include.php';
var_dump(incFn());
var_dump(array_map(fn($c) => is_a($c, 'stdClass'), ['stdClass']));
error_reporting(E_ALL & ~E_DEPRECATED);
var_dump(is_a('stdClass', 'stdClass'));
restore_error_handler();
var_dump(is_a('stdClass', 'stdClass'));
set_error_handler(function ($no, $str, $file, $line) {
    echo "warnings only: $str\n";
    return true;
}, E_WARNING);
var_dump(is_a('stdClass', 'stdClass'));
restore_error_handler();
set_error_handler(function ($no, $str, $file, $line) {
    echo "nested: ", basename($file), ":$line\n";
    return true;
});
error_reporting(E_ALL);
var_dump(array_filter(['stdClass'], function ($c) {
    return array_map(fn($d) => is_a($d, 'stdClass'), [$c])[0] || true;
}));
echo "done\n";
