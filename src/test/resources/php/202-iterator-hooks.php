<?php
class HookLike implements Iterator, ArrayAccess, Countable {
    public $callbacks = [10 => 'ten', 20 => 'twenty'];
    private $position = 0;
    public function rewind(): void { echo 'rewind|'; $this->position = 0; }
    public function valid(): bool { echo 'valid|'; return $this->position < count($this->callbacks); }
    public function current(): mixed { echo 'current|'; return array_values($this->callbacks)[$this->position]; }
    public function key(): mixed { echo 'key|'; return array_keys($this->callbacks)[$this->position]; }
    public function next(): void { echo 'next|'; $this->position++; }
    public function offsetExists($k): bool { return isset($this->callbacks[$k]); }
    public function offsetGet($k): mixed { return $this->callbacks[$k]; }
    public function offsetSet($k, $v): void { $this->callbacks[$k] = $v; }
    public function offsetUnset($k): void { unset($this->callbacks[$k]); }
    public function count(): int { return count($this->callbacks); }
}
$h = new HookLike;
foreach ($h as $key => $value) { echo "$key:$value|"; }
echo '\n';
foreach ($h as $value) { echo "$value|"; }
echo '\n';
class Aggregate implements IteratorAggregate {
    public function __construct(private $it) {}
    public function getIterator(): Traversable { echo 'aggregate|'; return $this->it; }
}
$a = new Aggregate(new Aggregate($h));
var_dump($a instanceof Traversable, is_iterable($a));
echo json_encode(iterator_to_array($a)), '\n';
echo iterator_count($h), '\n';
$n = 0;
echo iterator_apply($h, function () use (&$n) { $n++; return $n < 2; }), ':', $n, '\n';
try { foreach ($h as &$v) {} } catch (Error $e) { echo $e->getMessage(), '\n'; }
require __DIR__ . '/inc/spl-hooks.php';
$hook = new FixtureHook;
$hook->add(20, fn($value) => $value . 'B', 1);
$hook->add(10, fn($value, $extra) => $value . $extra, 2);
echo $hook->apply('x', 'A'), ':', count($hook), ':', isset($hook[10]) ? 'yes' : 'no', "\n";
