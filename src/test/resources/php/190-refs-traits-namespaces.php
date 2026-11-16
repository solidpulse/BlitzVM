<?php
namespace App\Util;

trait Counter {
    private int $hits = 0;
    public static $made = [];
    public function bump(int &$n, int $by = 1): int { $n += $by; return ++$this->hits; }
    public function &slot() { return $this->hits; }
    public static function make(...$args) { static::$made[] = static::class . ':' . implode(',', $args); return new static(...$args); }
}
class Box { use Counter; public function __construct(public readonly string $name = 'box', public array $items = []) {} }
class Crate { use Counter; public function __construct(public string $name = 'crate') {} }

function total(array &$xs, int ...$more) { foreach ($xs as &$x) { $x *= 2; } unset($x); return array_sum($xs) + array_sum($more); }
function strlen($s) { return 'ns-strlen'; }

$b = new Box('b1', [1, 2]);
$n = 5;
echo $b->bump($n, by: 3), " ", $n, "\n";
$r = &$b->slot(); $r = 40; echo $b->bump($n), " ", $n, "\n";
$args = [2, 3];
Box::make('x'); Crate::make(...$args);
echo implode(' | ', Box::$made), " / ", implode(' | ', Crate::$made), "\n";
$arr = [1, 2, 3];
echo total($arr, ...[10, 20]), " ", implode(',', $arr), "\n";
echo strlen('abc'), " ", \strlen('abc'), "\n";
[$a, [$c, $d]] = [1, [2, 3]];
$m = match (true) { $a > 1 => 'big', default => "small $c$d" };
echo $m, "\n";
$text = <<<EOT
  {$b->name} has {$b->items[1]} and $n
  EOT;
echo $text, "\n";
$i = 0; $acc = [];
loop:
$acc[] = &$arr[$i % 3];
if (++$i < 5) goto loop;
$acc[0] = 99; echo implode(',', $arr), "\n";
try { $b->name = 'x'; } catch (\Error $e) { echo get_class($e), ": ", $e->getMessage(), "\n"; }
$cb = [$b, 'bump']; $k = 1;
call_user_func_array($cb, [&$k, 4]); echo $k, "\n";
array_walk($arr, function (&$v, $key) { $v = $v + $key; }); echo implode(',', $arr), "\n";
echo __NAMESPACE__, " ", Box::class, " ", get_class(Crate::make()), "\n";
$GLOBALS['gv'] = 7; function readG() { global $gv; return $gv; } echo readG(), "\n";
// Dynamic callables: array callable to a trait method (using class passed), closure by reference.
$dyn = [$b, 'bump']; $q = 10;
echo $dyn($q, 5), " ", $q, "\n";
$stat = ['App\Util\Crate', 'make']; echo get_class($stat('dyn')), " ", end(Crate::$made), "\n";
$inc = function (&$v) { $v++; }; $z = 1; $inc($z); $inc($z); echo $z, "\n";
$name = 'App\Util\total'; $xs = [1, 1]; echo $name($xs, 1), " ", implode(',', $xs), "\n";
