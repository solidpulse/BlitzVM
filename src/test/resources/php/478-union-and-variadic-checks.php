<?php
// Object arguments to unions of plain classes, and typed variadics, in weak and strict callers.
set_error_handler(function ($no, $msg) { echo "warning: $msg\n"; return true; });
interface Shape { }
interface Named { }
class Base implements Shape { }
final class Square extends Base implements Named { }
final class Lone { }
final class Text implements Stringable { function __toString(): string { return 'text'; } }

function unionArg(Shape|Named|string $v): string { return is_object($v) ? get_class($v) : "string:$v"; }
function unionNullable(Lone|Named|null $v): string { return $v === null ? 'null' : get_class($v); }
function unionMissing(NotDeclaredAnywhere|Shape $v): string { return get_class($v); }
function strs(Stringable|string ...$parts): string { return implode(',', array_map(fn($p) => (string) $p, $parts)); }
function ints(int ...$n): string { return json_encode($n); }
function anything(mixed ...$xs): int { return count($xs); }
function floats(float ...$f): string { return json_encode($f); }

$cases = [
    fn() => unionArg(new Square()),
    fn() => unionArg(new Base()),
    fn() => unionArg('abc'),
    fn() => unionArg(42),
    fn() => unionArg(new Lone()),
    fn() => unionNullable(null),
    fn() => unionNullable(new Lone()),
    fn() => unionNullable(new Square()),
    fn() => unionNullable(new Base()),
    fn() => unionMissing(new Square()),
    fn() => unionMissing(new Lone()),
    fn() => strs('a', new Text(), 'b'),
    fn() => strs('a', 5, 2.5, true),
    fn() => strs('a', [1]),
    fn() => strs(),
    fn() => ints(1, '2', 3.0),
    fn() => ints(1, 'x'),
    fn() => ints(1, 2.5),
    fn() => anything(1, 'a', null, [], new Lone()),
    fn() => floats(1, 2.5, '3'),
    fn() => strs(...['k' => 'v', 'w' => 7]),
];
foreach ($cases as $i => $case) {
    try {
        echo $i, ': ', var_export($case(), true), "\n";
    } catch (TypeError $e) {
        echo $i, ': TypeError: ', preg_replace('/, called in .*/', '', $e->getMessage()), "\n";
    }
}
for ($r = 0; $r < 300; $r++) {
    unionArg(new Square()); unionArg(new Base()); unionArg('s'); strs('a', new Text()); ints(1, 2); anything(1);
}
echo unionArg(new Square()), ' ', unionArg(new Base()), ' ', strs('x', new Text()), "\n";
require __DIR__ . '/inc/478-strict.php';
