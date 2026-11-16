<?php
function inner() { $x = yield 'inner'; yield 'key' => $x; return 17; }
function outer() {
    yield from [5 => 'array', 's' => 'string'];
    $r = yield from inner();
    echo "return:$r|";
    yield 'after';
    return $r + 1;
}
$g = outer();
echo $g->key(), ':', $g->current(), '|';
$g->next();
echo $g->key(), ':', $g->current(), '|';
$g->next();
echo $g->key(), ':', $g->current(), '|';
echo $g->send('sent'), '|';
$g->next();
echo $g->key(), ':', $g->current(), '|';
$g->next();
echo $g->getReturn(), "\n";
function delegateIterator($it) { yield from $it; yield 9; }
class SmallIterator implements Iterator {
    private $i = 0;
    public function rewind(): void { $this->i = 0; }
    public function valid(): bool { return $this->i < 2; }
    public function current(): mixed { return $this->i + 3; }
    public function key(): mixed { return $this->i + 8; }
    public function next(): void { $this->i++; }
}
echo json_encode(iterator_to_array(delegateIterator(new SmallIterator))), "\n";
