<?php
// Bytecode 17c: array literals with & references (locals, elements, properties, nested) and
// spread of arrays and Traversables, preserving php's key rules.

function ref_local() {
    $a = 1;
    $arr = [&$a, 2];
    $a = 5;
    return $arr[0] . ',' . $arr[1];
}

function ref_keyed() {
    $a = 2;
    $arr = ['k' => &$a];
    $a = 7;
    $arr['k'] = 9;
    return $a . ',' . $arr['k'];
}

function ref_element() {
    $a = ['x' => 1];
    $arr = [&$a['x']];
    $arr[0] = 4;
    return $a['x'];
}

class RefHolder {
    public $p = 3;
    public function make() {
        $arr = [&$this->p];
        $arr[0] = 8;
        return $this->p;
    }
}

function spread_arrays() {
    $a = [1, 2];
    $b = [0 => 'z', 'k' => 'y'];
    return print_r([...$a, ...$b, 3], true);
}

function spread_traversable() {
    $it = new ArrayIterator(['a' => 1, 'b' => 2]);
    return print_r([...$it, 3], true);
}

function nested_ref() {
    $inner = [1, 2];
    $arr = ['n' => [&$inner[1]]];
    $arr['n'][0] = 10;
    return implode(',', $inner);
}

echo ref_local(), "\n";
echo ref_keyed(), "\n";
echo ref_element(), "\n";
echo (new RefHolder())->make(), "\n";
echo spread_arrays(), "\n";
echo spread_traversable(), "\n";
echo nested_ref(), "\n";
