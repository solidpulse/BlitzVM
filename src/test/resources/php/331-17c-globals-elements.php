<?php
// Bytecode 17c: $GLOBALS elements as places: nested writes and reads, isset, unset,
// dynamic keys, compound assign, =&, by-reference foreach, destructuring and a by-ref builtin.

function nested_write() {
    $GLOBALS['x']['y'] = 1;
    return $GLOBALS['x']['y'];
}

function dynamic_key($key) {
    $GLOBALS[$key] = 'v';
    return $GLOBALS[$key];
}

function set_and_read() {
    $GLOBALS['g'] = 5;
    return $GLOBALS['g'];
}

function nested_isset() {
    return (isset($GLOBALS['x']) ? 'yes' : 'no') . ',' . (isset($GLOBALS['x']['y']) ? 'yes' : 'no');
}

function unset_element() {
    $GLOBALS['z'] = 1;
    unset($GLOBALS['z']);
    return isset($GLOBALS['z']) ? 'yes' : 'no';
}

function compound() {
    $GLOBALS['n'] = 4;
    $GLOBALS['n'] += 1;
    $GLOBALS['n']++;
    return $GLOBALS['n'];
}

function reference() {
    $r = &$GLOBALS['g'];
    $r = 6;
    return $GLOBALS['g'];
}

function foreach_reference() {
    $GLOBALS['arr'] = [1, 2];
    foreach ($GLOBALS['arr'] as &$v) {
        $v++;
    }
    unset($v);
    return implode(',', $GLOBALS['arr']);
}

function destructure() {
    $GLOBALS['list'] = [1, 2];
    [$GLOBALS['list'][0], $GLOBALS['list'][1]] = [7, 8];
    return implode(',', $GLOBALS['list']);
}

function sort_element() {
    $GLOBALS[0] = [3, 1, 2];
    sort($GLOBALS[0]);
    return implode(',', $GLOBALS[0]);
}

function deep_dynamic($key) {
    $GLOBALS[$key]['deep'] = 9;
    return $GLOBALS[$key]['deep'];
}

echo nested_write(), "\n";
echo dynamic_key('dyn'), "\n";
echo set_and_read(), "\n";
echo nested_isset(), "\n";
echo unset_element(), "\n";
echo compound(), "\n";
echo reference(), "\n";
echo foreach_reference(), "\n";
echo destructure(), "\n";
echo sort_element(), "\n";
echo deep_dynamic('nest'), "\n";
