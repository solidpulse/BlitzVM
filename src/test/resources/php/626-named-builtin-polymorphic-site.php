<?php
// One call site that sees many builtins (more than the plan cache holds), and one that
// alternates between a builtin, a closure and a user function.
function mypad($string, $length) {
    return "u:$string:$length";
}

$fns = ['strtolower', 'strtoupper', 'ucfirst', 'lcfirst', 'trim', 'strrev'];
foreach ($fns as $f) {
    var_dump($f(string: ' AbC '));
}

$refs = ['sort', 'rsort', 'ksort', 'krsort', 'asort', 'arsort'];
foreach ($refs as $f) {
    $a = [3, 1, 2];
    var_dump($f(array: $a, flags: SORT_NUMERIC));
    var_dump($a);
}

$mixed = ['str_pad', fn($string, $length) => "c:$string:$length", 'mypad'];
foreach ($mixed as $f) {
    var_dump($f(string: 'x', length: 3));
}

$replacements = ['str_replace', 'str_ireplace'];
foreach ($replacements as $f) {
    $c = null;
    var_dump($f(search: 'a', replace: 'b', subject: 'aaa', count: $c));
    var_dump($c);
}
