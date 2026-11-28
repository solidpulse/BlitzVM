<?php
class Tag {
    public function __construct(public string $name = "tag") {}
}

function describe($first, $second = "B", $third = "C") {
    return "$first/$second/$third";
}
echo describe(second: "2", first: 1), "\n";
echo describe(1, third: 3), "\n";
echo describe(first: 1), "\n";

function defaults($a = PHP_INT_MAX, $b = new Tag(), $c = 1 + 2) {
    return "$a/{$b->name}/$c";
}
echo defaults(c: 9), "\n";
echo defaults(b: new Tag(name: "custom")), "\n";

function collect($head, ...$rest) {
    return $head . ":" . json_encode($rest);
}
echo collect(1, two: 2, three: 3), "\n";
echo collect(head: "h", alpha: "a"), "\n";

function describeArgs($a = 1, $b = 2) {
    return json_encode([func_num_args(), func_get_args()]);
}
echo describeArgs(b: 20), "\n";

class Point {
    public function __construct(public int $x = 0, public int $y = 0, public readonly string $label = "p") {}
    public function move($dx, $dy = 1) {
        return [$this->x + $dx, $this->y + $dy];
    }
    public static function make($x, $y = 0, $label = "s") {
        return new self(label: $label, x: $x, y: $y);
    }
    public static function viaStatic($x) {
        return static::make(y: $x * 2, x: $x, label: "st");
    }
}

$p = new Point(y: 4, label: "q");
echo "{$p->x},{$p->y},{$p->label}\n";
print_r($p->move(dy: 10, dx: 2));
$q = Point::make(y: 7, x: 3, label: "m");
echo "{$q->x},{$q->y},{$q->label}\n";
print_r(Point::viaStatic(x: 5)->move(dx: 1));

class Point3 extends Point {}
print_r(Point3::viaStatic(x: 6));

$closure = function ($left, $right = "R") {
    return "c:$left,$right";
};
echo $closure(right: 2, left: 1), "\n";

function plain($value, $suffix = "!") {
    return $value . $suffix;
}
$fcc = plain(...);
echo $fcc(suffix: "?", value: "v"), "\n";
$method = $p->move(...);
print_r($method(dx: 1, dy: 2));

class Config {
    public function __construct(public readonly string $host, public readonly int $port = 443) {}
}
$config = new Config(port: 8080, host: "example");
echo "$config->host:$config->port\n";

class Store {
    public function __construct(public string $name = "s", protected array $items = [], private int $count = 0) {}
    public function summary() {
        return "$this->name/" . count($this->items) . "/$this->count";
    }
}
echo (new Store(items: [1, 2], name: "box"))->summary(), "\n";
