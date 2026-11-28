<?php
// A trait supplies the ArrayAccess, Countable and Iterator methods for two classes.
trait Arrayish {
    private array $items = [1, 2, 3];
    public function offsetExists(mixed $o): bool { return array_key_exists($o, $this->items); }
    public function offsetGet(mixed $o): mixed { return $this->items[$o]; }
    public function offsetSet(mixed $o, mixed $v): void {
        if ($o === null) { $this->items[] = $v; } else { $this->items[$o] = $v; }
    }
    public function offsetUnset(mixed $o): void { unset($this->items[$o]); }
    public function count(): int { return count($this->items); }
}
trait Walkable {
    private int $at = 0;
    public function current(): mixed { return $this->items[$this->at]; }
    public function key(): mixed { return $this->at; }
    public function next(): void { $this->at++; }
    public function rewind(): void { $this->at = 0; }
    public function valid(): bool { return $this->at < count($this->items); }
}
final class TraitA implements ArrayAccess, Countable, Iterator { use Arrayish, Walkable; }
final class TraitB implements ArrayAccess, Countable, Iterator { use Arrayish, Walkable; }

foreach ([new TraitA(), new TraitB()] as $o) {
    var_dump(isset($o[0]));
    var_dump($o[1] ?? 'default');
    var_dump($o[1]);
    $o[1] = 20;
    $o[] = 4;
    var_dump($o[1]);
    var_dump(count($o));
    foreach ($o as $k => $v) { echo $k, '=', $v, "\n"; }
    unset($o[0]);
    var_dump(isset($o[0]));
    var_dump(count($o));
}
