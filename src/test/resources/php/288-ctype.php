<?php
$functions = [
    "ctype_digit", "ctype_alpha", "ctype_alnum", "ctype_upper", "ctype_lower",
    "ctype_space", "ctype_punct", "ctype_xdigit", "ctype_print", "ctype_graph", "ctype_cntrl",
];
$values = ["123", "12a", "", " ", "\t\n\r\f\x0b", "abc", "ABC", "aBc", "a1", "!", "0", "0x1F", "\x00", "\x7f", "\x80", "é", null, true, false, 1.5];
foreach ($values as $v) {
    echo json_encode($v), ": ";
    foreach ($functions as $f) {
        echo var_export($f($v), true), " ";
    }
    echo "\n";
}
set_error_handler(function ($errno, $errstr) {
    echo "deprecated:", $errno, ":", $errstr, "\n";
    return true;
});
foreach ([49, 48, 256, -1, -129, 65, 10, 32] as $n) {
    echo $n, ": ", var_export(ctype_digit($n), true), " ", var_export(ctype_alpha($n), true), " ",
        var_export(ctype_space($n), true), " ", var_export(ctype_cntrl($n), true), "\n";
}
restore_error_handler();
var_dump(ctype_digit("49"));
