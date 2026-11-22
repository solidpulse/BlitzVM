<?php
declare(strict_types=1);
set_error_handler(function ($n, $s) { echo "warning:$s\n"; return true; });

function takes_float(float $f) { return $f; }
function takes_union(int|string $v) { return $v; }
function takes_int(int $i) { return $i; }
function takes_string(string $s) { return $s; }
function takes_bool_or_int(bool|int $v) { return $v; }
function takes_nullable_obj(?TypedTarget $t) { return $t; }

final class TypedTarget {
    public function withSelf(self $other): self { return $other; }
    public function unionOfClasses(Countable|Traversable $c): Countable|Traversable { return $c; }
    public function intersect(Countable&Traversable $c): int { return count($c); }
}

// Strict caller: exact matches and int-to-float pass, everything else is a TypeError.
$c = new TypedTarget();
var_dump(takes_float(5), takes_union("5"), takes_union(5), $c->withSelf($c) === $c);
var_dump($c->unionOfClasses(new ArrayIterator([1])) instanceof Traversable, $c->intersect(new ArrayObject([1, 2])));
foreach ([fn () => takes_int("42"), fn () => takes_string(12), fn () => takes_union(5.5),
          fn () => $c->withSelf(new stdClass), fn () => $c->unionOfClasses(new stdClass),
          fn () => $c->intersect(new ArrayIterator([]) instanceof Countable ? new stdClass : new stdClass)] as $f) {
    try { $f(); } catch (TypeError $e) { echo preg_replace('/called in .*on line/', 'called in FILE on line', $e->getMessage()), "\n"; }
}

// Weak caller (a file without strict_types) calling the same functions: coercion applies.
require __DIR__ . '/inc/490-weak.php';
weak_calls();
for ($i = 0; $i < 3; $i++) { var_dump(takes_float($i)); }
