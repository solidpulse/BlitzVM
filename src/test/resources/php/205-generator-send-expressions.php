<?php
function bump(&$n) { return ++$n; }
function combineYield($a, $b, $c) { return "$a:$b:$c"; }
function conversation() {
    $a = yield 'first';
    echo "a:$a|";
    $b = yield 'second';
    echo "b:$b|";
    return $a + $b;
}
$g = conversation();
echo $g->current(), '|', $g->send(4), '|';
var_dump($g->send(7), $g->getReturn());
function expressions() {
    $n = 0;
    $result = bump($n) + (yield 'operand');
    echo "$n:$result|";
    $result = combineYield(bump($n), yield 'arg', bump($n));
    echo "$n:$result|";
    $a = [bump($n), yield 'array', bump($n)];
    echo "$n:", json_encode($a), '|';
    return $n;
}
$g = expressions();
echo $g->current(), '|', $g->send(10), '|', $g->send('ok'), '|';
$g->send(20);
echo $g->getReturn(), "\n";
$arrow = fn($v) => yield $v;
echo json_encode(iterator_to_array($arrow('arrow'))), "\n";
