<?php
// Bytecode 17c: ??= on index lvalues, properties, static properties and $GLOBALS
// elements; the place's keys are evaluated once and the value only when unset.

$calls = 0;

function side() {
    global $calls;
    $calls++;
    return 7;
}

function index() {
    $a = [];
    $a['k'] ??= 5;
    $a['k'] ??= 7;
    return $a['k'];
}

function keys_once() {
    $i = 0;
    $a = [];
    $a[$i++] ??= 1;
    $a[$i++] ??= 2;
    return $i . ':' . implode(',', $a);
}

class CoalesceTarget {
    public $p;
    public static $s;
}

function property() {
    $o = new CoalesceTarget();
    $o->p ??= 3;
    $o->p ??= 4;
    return $o->p;
}

function static_property() {
    CoalesceTarget::$s ??= 5;
    CoalesceTarget::$s ??= 6;
    return CoalesceTarget::$s;
}

function global_element() {
    $GLOBALS['g'] ??= 9;
    $GLOBALS['g'] ??= 10;
    return $GLOBALS['g'];
}

function nested_index() {
    $a = [];
    $a['x']['y'] ??= 1;
    $a['x']['y'] ??= 2;
    return $a['x']['y'];
}

function lazy_value() {
    global $calls;
    $a = ['k' => 1];
    $a['k'] ??= side();
    $a['z'] ??= side();
    return $calls . ':' . $a['z'];
}

echo index(), "\n";
echo keys_once(), "\n";
echo property(), "\n";
echo static_property(), "\n";
echo global_element(), "\n";
echo nested_index(), "\n";
echo lazy_value(), "\n";
echo $calls, "\n";
