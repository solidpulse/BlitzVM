<?php
// Bytecode 17c: =& on locals, nested array elements, properties, static properties and a
// non-lvalue (the notice), plus rebinding and a later read through the old cell.

function aliases() {
    $a = 1;
    $b = &$a;
    $b = 2;
    return "$a,$b";
}

function nested_element() {
    $arr = ['k' => ['j' => 5]];
    $a = &$arr['k']['j'];
    $a = 9;
    return $arr['k']['j'];
}

function rebinding() {
    $x = 1;
    $y = 2;
    $r = &$x;
    $r = &$y;
    $r = 3;
    return "$x,$y,$r";
}

class RefTarget {
    public static $v = 0;
    public $p = 0;
}

function static_target() {
    $a = 7;
    RefTarget::$v = &$a;
    $a = 8;
    return RefTarget::$v;
}

function property_target() {
    $a = 9;
    $o = new RefTarget();
    $o->p = &$a;
    $a = 10;
    return $o->p;
}

function element_target() {
    $a = ['x' => 1];
    $r = &$a['x'];
    $r = 2;
    return $a['x'];
}

function make() {
    return [1, 2];
}

function non_lvalue() {
    $r = &make();
    $r = 5;
    return $r;
}

function value_copy() {
    $a = 1;
    $b = &$a;
    $c = $b;
    $b = 3;
    return "$a,$b,$c";
}

echo aliases(), "\n";
echo nested_element(), "\n";
echo rebinding(), "\n";
echo static_target(), "\n";
echo property_target(), "\n";
echo element_target(), "\n";
echo non_lvalue(), "\n";
echo value_copy(), "\n";
