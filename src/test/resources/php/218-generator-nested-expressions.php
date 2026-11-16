<?php
function nestedExpressions() {
    $n = 0;
    echo 'prefix|', (yield 'echo'), '|suffix|';
    $a = [];
    $a[$n++] = yield 'assignment';
    echo $n, ':', json_encode($a), '|';
    $m = match (2) { 1 => 'wrong', 2 => yield 'match', default => 'wrong' };
    echo $m, '|';
    $v = (yield 'condition') ? (yield 'true') : (yield 'false');
    echo $v, '|';
    $i = 0;
    while (yield "test:$i") { yield "body:$i"; $i++; }
    return $i;
}
$g = nestedExpressions();
echo $g->current(), '|';
echo $g->send('E'), '|';
echo $g->send('A'), '|';
echo $g->send('M'), '|';
echo $g->send(true), '|';
echo $g->send('T'), '|';
echo $g->send(true), '|';
$g->next();
echo $g->current(), '|';
$g->send(false);
echo $g->getReturn(), "\n";
