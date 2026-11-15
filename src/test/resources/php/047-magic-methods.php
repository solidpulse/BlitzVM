<?php

class Bag {
    private array $data = ['a' => 10];
    private string $secret = 'hidden';
    public array $log = [];

    public function __get(string $name): mixed {
        $this->log[] = "get:$name";
        if ($name === 'secret') {
            return 'intercepted:' . $this->secret;
        }
        return $this->data[$name] ?? null;
    }

    public function __set(string $name, mixed $value): void {
        $this->log[] = "set:$name=$value";
        $this->data[$name] = $value;
    }

    public function __isset(string $name): bool {
        $this->log[] = "isset:$name";
        return isset($this->data[$name]);
    }

    public function __unset(string $name): void {
        $this->log[] = "unset:$name";
        unset($this->data[$name]);
    }

    public function __call(string $name, array $args): string {
        return "call:$name(" . implode(',', $args) . ')';
    }

    public static function __callStatic(string $name, array $args): string {
        return static::class . "::callStatic:$name(" . implode(',', $args) . ')';
    }

    public function __toString(): string {
        return 'Bag(' . count($this->data) . ')';
    }

    public function __clone(): void {
        $this->log[] = 'cloned';
    }
}

class SubBag extends Bag {}

$b = new Bag();
echo $b->a, "\n";
echo $b->secret, "\n";
$b->x = 5;
$b->x += 3;
echo $b->x, "\n";
var_dump(isset($b->a), isset($b->missing));
echo ($b->a ?? -1), '|', ($b->missing ?? -1), "\n";
unset($b->a);
var_dump(isset($b->a));

echo $b->foo(1, 'two'), "\n";
echo Bag::bar(3, 4), "\n";
echo SubBag::baz(5), "\n";

echo $b, "\n";
echo "str=" . $b . "|{$b}\n";
var_dump((string)$b, $b == 'Bag(1)', $b == 'other', strlen($b), strtoupper($b));

$c = clone $b;
$c->y = 99;
echo "orig log: ", implode(',', $b->log), "\n";
echo "clone log: ", implode(',', $c->log), "\n";
var_dump(isset($b->y), isset($c->y));
