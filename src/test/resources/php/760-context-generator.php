<?php
final class Counter {
    public int $value = 0;
    public function bump(): int { return ++$this->value; }
}
function values(Counter $c): Generator {
    yield $c->bump();
    yield $c->bump();
    yield $c->bump();
}
function consume(Counter $c): void {
    foreach (values($c) as $key => $value) { echo "gen:$key:$value\n"; }
}
function manual(Counter $c): void {
    $g = values($c);
    echo "cur:", $g->current(), "\n";
    $g->next();
    echo "next:", $g->current(), "\n";
    $g->next();
    echo "last:", $g->current(), "\n";
    var_dump($g->valid());
    $g->next();
    var_dump($g->valid());
}
$c = new Counter();
consume($c);
manual($c);
