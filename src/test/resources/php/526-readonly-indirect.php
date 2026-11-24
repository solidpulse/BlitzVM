<?php
// Readonly properties: indirect modification ([]=) and ++ must both throw outside the
// declaring scope, for scalar and array properties, and the value must stay unchanged.
class ReadonlyHolder
{
    public readonly int $number;
    public readonly array $items;

    public function __construct()
    {
        $this->number = 1;
        $this->items = [1];
    }
}

$r = new ReadonlyHolder();
try {
    $r->number++;
} catch (Throwable $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
try {
    ++$r->number;
} catch (Throwable $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
try {
    $r->items[] = 2;
} catch (Throwable $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
try {
    $r->items[0] = 3;
} catch (Throwable $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
try {
    unset($r->number);
} catch (Throwable $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
var_dump($r->number, $r->items);

class ReadonlyChild extends ReadonlyHolder
{
    public function touch(): void
    {
        $this->items[] = 9;
    }
}

try {
    (new ReadonlyChild())->touch();
    echo "child touched\n";
} catch (Throwable $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
