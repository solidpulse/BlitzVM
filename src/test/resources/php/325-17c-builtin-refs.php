<?php
// Bytecode 17c: builtin by-reference arguments, including an array element, a property,
// a local variable, and a call result (which gets php's passing-by-reference notice).

function sort_element($a) {
    sort($a['k']);
    return $a['k'];
}

echo implode(',', sort_element(['k' => [3, 1, 2]])), "\n";

class Matcher {
    public $m = [];
    public function match($s) {
        $r = preg_match('/b+/', $s, $this->m);
        return $r . ':' . implode(',', $this->m);
    }
}

echo (new Matcher())->match('abbbc'), "\n";

function numbers() {
    return [1, 2, 3];
}

function last_of_result() {
    return end(numbers());
}

echo last_of_result(), "\n";

function sort_local($list) {
    sort($list);
    return implode(',', $list);
}

echo sort_local([5, 2, 9]), "\n";

function push_local($list) {
    array_push($list, 4, 5);
    return implode(',', $list);
}

echo push_local([1, 2, 3]), "\n";
