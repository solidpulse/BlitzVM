<?php
// ArrayAccess on two classes at one site: isset, ??, read, write, append, unset, nested.
final class BagA implements ArrayAccess {
    public array $d = ['a' => 1, 'b' => [1, 2, 3]];
    public function offsetExists(mixed $o): bool { return array_key_exists($o, $this->d); }
    public function offsetGet(mixed $o): mixed { return $this->d[$o] ?? null; }
    public function offsetSet(mixed $o, mixed $v): void {
        if ($o === null) { $this->d[] = $v; } else { $this->d[$o] = $v; }
    }
    public function offsetUnset(mixed $o): void { unset($this->d[$o]); }
}
final class BagB implements ArrayAccess {
    public array $d = ['a' => 1, 'b' => [1, 2, 3]];
    public function offsetExists(mixed $o): bool { return array_key_exists($o, $this->d); }
    public function offsetGet(mixed $o): mixed { return $this->d[$o] ?? null; }
    public function offsetSet(mixed $o, mixed $v): void {
        if ($o === null) { $this->d[] = $v; } else { $this->d[$o] = $v; }
    }
    public function offsetUnset(mixed $o): void { unset($this->d[$o]); }
}

foreach ([new BagA(), new BagB()] as $o) {
    var_dump(isset($o['a']));
    var_dump(isset($o['zz']));
    var_dump($o['a'] ?? 'default');
    var_dump($o['zz'] ?? 'default');
    var_dump($o['a']);
    $o['a'] = 5;
    var_dump($o['a']);
    $o[] = 9;
    var_dump($o[0]);
    unset($o['a']);
    var_dump(isset($o['a']));
    var_dump($o['b'][1]);
    var_dump(isset($o['b'][2]));
    var_dump(isset($o['b'][5]));
    var_dump($o['b'][5] ?? 'missing');
}
