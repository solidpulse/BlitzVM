<?php
function order($second, $first) {
    if (false) { $later = 1; }
    $z = 2;
    extract(['dyn' => 3, 'later' => 4]);
    $a = 5;
    echo implode(',', array_keys(get_defined_vars())), "\n";
    unset($z); $z = 6;
    echo implode(',', array_keys(get_defined_vars())), "\n";
    $snapshot = get_defined_vars();
    $snapshot['later'] = 99;
    echo "$later\n";
}
order(1, 2);
function compact_order() {
    $z = 1;
    extract(['b' => 2, 'a' => 3]);
    compact('a', 'b');
    $q = 4;
    echo implode(',', array_keys(get_defined_vars())), "\n";
}
compact_order();
class DefinedScope {
    function f($p) { $a = 1; echo implode(',', array_keys(get_defined_vars())), "\n"; }
}
(new DefinedScope)->f(3);
$v = 9;
$capture_order = function ($param) use ($v) {
    $a = 1; echo implode(',', array_keys(get_defined_vars())), "\n";
};
$capture_order(8);
function defined_references() {
    $a = 1; $b = &$a; $plain = 3;
    $vars = get_defined_vars();
    $vars['a'] = 5; $vars['plain'] = 7;
    echo "$a:$b:$plain\n";
    $copy = compact('a', 'b'); $copy['a'] = 9;
    echo "$a:$b\n";
}
defined_references();
