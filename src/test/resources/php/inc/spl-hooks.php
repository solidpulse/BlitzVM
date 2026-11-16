<?php
// Minimal WP_Hook-shaped iterator and ArrayAccess implementation used by Task 07 fixtures.
class FixtureHook implements Iterator, ArrayAccess, Countable {
    private $callbacks = [];
    private $keys = [];
    private $position = 0;
    public function add($priority, $callback, $accepted) { $this->callbacks[$priority][] = [$callback, $accepted]; ksort($this->callbacks); }
    public function apply($value, ...$args) {
        foreach ($this as $priority => $callbacks) {
            foreach ($callbacks as [$callback, $accepted]) {
                $value = $callback(...array_slice([$value, ...$args], 0, $accepted));
            }
        }
        return $value;
    }
    public function rewind(): void { $this->keys = array_keys($this->callbacks); $this->position = 0; }
    public function valid(): bool { return $this->position < count($this->keys); }
    public function key(): mixed { return $this->keys[$this->position]; }
    public function current(): mixed { return $this->callbacks[$this->key()]; }
    public function next(): void { $this->position++; }
    public function count(): int { return count($this->callbacks); }
    public function offsetExists($key): bool { return isset($this->callbacks[$key]); }
    public function offsetGet($key): mixed { return $this->callbacks[$key]; }
    public function offsetSet($key, $value): void { $this->callbacks[$key] = $value; }
    public function offsetUnset($key): void { unset($this->callbacks[$key]); }
}
