<?php

class Holder {
    public array $data = [];
}

function bump(&$x) {
    $x++;
}

function refPlace() {
    $h = new Holder();
    $h->data['inner'] = ['a' => 1, 'b' => 2];
    $copy = $h->data['inner'];
    $n = extract($h->data['inner'], EXTR_REFS);
    $a = 10;
    $h->data['inner']['b'] = 20;
    echo $n, ':', $a, ':', $b, ':', json_encode($h->data['inner']), ':', json_encode($copy), "\n";
    unset($a);
    $h->data['inner']['a'] = 3;
    echo isset($a) ? 'set' : 'unset', ':', $b, "\n";
}
refPlace();

function dynamicGlobal() {
    $GLOBALS['dg'] = 4;
    $name = 'dg';
    global $$name;
    bump($$name);
    echo $GLOBALS['dg'], ':', $dg, "\n";
    $GLOBALS['dg'] = 9;
    echo $dg, "\n";
}
dynamicGlobal();

function asserts() {
    $s = 0;
    $r = assert($s = 1);
    var_dump($r, $s);
    var_dump(assert(false, 'ignored'));
}
asserts();
