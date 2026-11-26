<?php
// __get, __isset and __set on two classes at one site.
final class MagicA {
    private array $d = [];
    public function __get(string $n): mixed { return $this->d[$n] ?? null; }
    public function __isset(string $n): bool { return isset($this->d[$n]); }
    public function __set(string $n, mixed $v): void { $this->d[$n] = $v; }
}
final class MagicB {
    private array $d = [];
    public function __get(string $n): mixed { return $this->d[$n] ?? null; }
    public function __isset(string $n): bool { return isset($this->d[$n]); }
    public function __set(string $n, mixed $v): void { $this->d[$n] = $v; }
}

foreach ([new MagicA(), new MagicB()] as $o) {
    var_dump(isset($o->x));
    var_dump($o->x ?? 'default');
    $o->x = 5;
    var_dump(isset($o->x));
    var_dump($o->x);
    $o->y = 'text';
    var_dump($o->y);
    unset($o->y);
    var_dump(isset($o->y));
}
