<?php
function values($n) {
    echo "start:$n|";
    yield;
    yield 5 => 'five';
    yield 'six';
    yield 'name' => 'named';
    yield 'seven';
    return 42;
}
$g = values(3);
echo get_class($g), ':', ($g instanceof Iterator ? 'iterator' : 'no'), "\n";
var_dump($g->valid(), $g->key(), $g->current());
foreach ($g as $k => $v) { echo json_encode($k), ':', json_encode($v), '|'; }
echo "\n", $g->getReturn(), "\n";
var_dump($g->valid(), $g->current(), $g->key());
function lazy($a) { echo 'body|'; yield $a; }
$g = lazy('bound');
echo 'created|';
echo $g->current(), "\n";
function keyEdges() { yield -3 => 'a'; yield 'b'; yield 10 => 'c'; yield 2 => 'd'; yield 'e'; }
echo json_encode(iterator_to_array(keyEdges())), "\n";
