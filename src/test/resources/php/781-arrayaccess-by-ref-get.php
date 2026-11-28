<?php
// offsetGet returning a reference: assigns and increments must write through the cell.
final class RefA implements ArrayAccess {
    public array $d = ['x' => 1, 'y' => 2];
    public function offsetExists(mixed $o): bool { return isset($this->d[$o]); }
    public function &offsetGet(mixed $o): mixed { return $this->d[$o]; }
    public function offsetSet(mixed $o, mixed $v): void { $this->d[$o] = $v; }
    public function offsetUnset(mixed $o): void { unset($this->d[$o]); }
}
final class RefB implements ArrayAccess {
    public array $d = ['x' => 1, 'y' => 2];
    public function offsetExists(mixed $o): bool { return isset($this->d[$o]); }
    public function &offsetGet(mixed $o): mixed { return $this->d[$o]; }
    public function offsetSet(mixed $o, mixed $v): void { $this->d[$o] = $v; }
    public function offsetUnset(mixed $o): void { unset($this->d[$o]); }
}

foreach ([new RefA(), new RefB()] as $o) {
    var_dump($o['x']);
    $v = &$o['x'];
    $v = 42;
    var_dump($o['x']);
    unset($v);
    $o['x'] += 5;
    var_dump($o['x']);
    $o['z'] = 7;
    $o['z']++;
    var_dump($o['z']);
    unset($o['z']);
    var_dump(isset($o['z']));
    var_dump($o['y']);
}
