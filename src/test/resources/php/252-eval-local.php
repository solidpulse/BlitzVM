<?php
function local_eval($arg) {
    $x = 4;
    $r = eval('$x += $arg; $made = 7; return $x;');
    echo "$x:$made:$r\n";
    echo eval('$x++;') === null ? 'null' : 'wrong', ":$x\n";
}
local_eval(3);
class EvalScope {
    private $p = 11;
    const C = 'class';
    function f() { echo eval('return $this->p . ":" . self::C;'), "\n"; }
}
(new EvalScope)->f();
