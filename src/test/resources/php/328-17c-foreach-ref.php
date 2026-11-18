<?php
// Bytecode 17c: foreach by reference over locals, array elements, properties, temporaries
// and objects; a write to the variable after the loop still aliases the last element.

function local_array() {
    $a = [1, 2, 3];
    foreach ($a as &$v) {
        $v *= 2;
    }
    $v = 20;
    return implode(',', $a) . ':' . $v;
}

function element_array() {
    $a = ['k' => [1, 2, 3]];
    foreach ($a['k'] as &$v) {
        $v += 10;
    }
    $v = 99;
    return implode(',', $a['k']) . ':' . $v;
}

class Bag {
    public $items = [4, 5, 6];
    public function bump() {
        foreach ($this->items as &$v) {
            $v += 1;
        }
        $v = 7;
        return implode(',', $this->items) . ':' . $v;
    }
    public function keys() {
        $out = '';
        foreach ($this->items as $k => &$v) {
            $out .= "$k=$v,";
        }
        unset($v);
        return $out;
    }
}

function loop_control() {
    $a = [1, 2, 3, 4];
    foreach ($a as &$v) {
        if ($v == 2) {
            continue;
        }
        if ($v == 4) {
            break;
        }
        $v = $v * 10;
    }
    unset($v);
    return implode(',', $a);
}

function nested() {
    $a = [[1, 2], [3, 4]];
    foreach ($a as &$row) {
        foreach ($row as &$v) {
            $v += 1;
        }
        unset($v);
    }
    unset($row);
    return implode(',', $a[0]) . '|' . implode(',', $a[1]);
}

function temporary() {
    $out = [];
    foreach (numbers() as &$v) {
        $out[] = $v * 2;
    }
    unset($v);
    return implode(',', $out);
}

function numbers() {
    return [1, 2, 3];
}

function removed_during_loop() {
    $a = [1, 2, 3];
    $seen = [];
    foreach ($a as &$v) {
        $seen[] = $v;
        if ($v == 1) {
            unset($a[1]);
        }
    }
    unset($v);
    return implode(',', $seen);
}

function object_properties() {
    $o = new stdClass();
    $o->a = 1;
    $o->b = 2;
    foreach ($o as &$v) {
        $v *= 3;
    }
    unset($v);
    return $o->a . ',' . $o->b;
}

echo local_array(), "\n";
echo element_array(), "\n";
$bag = new Bag();
echo $bag->bump(), "\n";
echo $bag->keys(), "\n";
echo loop_control(), "\n";
echo nested(), "\n";
echo temporary(), "\n";
echo removed_during_loop(), "\n";
echo object_properties(), "\n";
