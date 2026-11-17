<?php
function f() { return ['a' => 1, 'b' => [1, 2], 3]; }
$x = f(); $x['a'] = 9; $x['b'][] = 3; $y = f();
var_dump($x === $y, $y['a'], count($y['b']));
$e = []; $e[] = 1; $e2 = []; var_dump(count($e2));
$r = [1, 2, 3]; next($r); var_dump(current($r)); $r2 = [1, 2, 3]; var_dump(current($r2));
$s = [3, 1, 2]; sort($s); $s2 = [3, 1, 2]; var_dump($s2[0]);
foreach ([1, 2] as &$v) { $v *= 10; } unset($v);
$z = [1, 2]; foreach ($z as $k => &$v) { $v++; } unset($v); echo $z[0], $z[1], "\n"; var_dump([1, 2]);
function g(array $p) { $p[] = 5; array_push($p, 6); return $p; } var_dump(count(g([1])), count([1]));
for ($i = 0; $i < 3; $i++) { $l = ['n' => 0]; $l['n'] += $i; echo $l['n'], ' '; } echo "\n";
$q = ['k' => [1]]; $ref = &$q['k']; $ref[] = 2; var_dump(count($q['k'])); 
$w = [5, 6]; array_shift($w); $w2 = [5, 6]; var_dump($w2[0]);
function lit() { return [1, 'k' => ['x' => [true, null, -2, 1.5]]]; }
$m = lit(); $m['k']['x'][] = 'new'; unset($m[0]); var_dump(lit() === [1, 'k' => ['x' => [true, null, -2, 1.5]]], count($m['k']['x']));
$n = lit(); array_splice($n['k']['x'], 0, 2); var_dump(count(lit()['k']['x']));
$o = [1, 2]; $p = &$o; $p[] = 3; var_dump(count([1, 2]), count($o));
$keys = [1 => 'a', '2' => 'b', true => 'c', null => 'd']; var_dump($keys);
