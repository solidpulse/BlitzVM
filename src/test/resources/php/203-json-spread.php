<?php
class JsonValue implements JsonSerializable {
    public function __construct(private $value) {}
    public function jsonSerialize(): mixed { return $this->value; }
}
echo json_encode(new JsonValue(['nested' => new JsonValue(['a' => 1, 'b' => 2]), 'scalar' => new JsonValue('ok')])), '\n';
class SelfJson implements JsonSerializable {
    public $v = 3;
    public function jsonSerialize(): mixed { return $this; }
}
echo json_encode(new SelfJson), '\n';
class PairIterator implements Iterator {
    private $pos = 0;
    public function rewind(): void { $this->pos = 0; }
    public function valid(): bool { return $this->pos < 2; }
    public function current(): mixed { return $this->pos + 4; }
    public function key(): mixed { return $this->pos ? 'b' : 'a'; }
    public function next(): void { $this->pos++; }
}
function combine($a, $b) { return "$a:$b"; }
$it = new PairIterator;
echo combine(...$it), '\n';
echo json_encode(['x' => 1, ...$it]), '\n';
echo json_encode(iterator_to_array($it, false)), '\n';
