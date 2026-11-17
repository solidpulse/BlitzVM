<?php
// Untimed subset checks; not a conformance fixture for the production runtime.
function mutate($a) {
    $a['nested']['x'] = 9;
    $a[] = 12;
    return $a;
}
function later($x, $d = 2) { return helper($x) + $d; }
function helper($x) {
    if ($x <= 1) { return 1; }
    return $x * helper($x - 1);
}
function overflow_add($x, $y) { return $x + $y; }
function overflow_sub($x, $y) { return $x - $y; }
function overflow_mul($x, $y) { return $x * $y; }
$a = ['nested' => ['x' => 1], 'nil' => null];
$b = $a;
$b['nested']['x'] = 4;
$c = mutate($a);
echo $a['nested']['x'], ':', $b['nested']['x'], ':', $c['nested']['x'], ':', count($c), "\n";
echo isset($a['nil']), ':', array_key_exists('nil', $a), ':', isset($a['missing']), "\n";
$sum = 0;
$list = [2, 3, 4];
foreach ($list as $key => $value) {
    $list[] = 100;
    if ($key === 1) { continue; }
    $sum = $sum + $value;
}
echo $sum, ':', count($list), ':', later(5), "\n";
$i = 0;
while ($i < 10) {
    $i = $i + 1;
    if ($i === 3) { break; }
}
echo $i, ':', 2.5 * 2, ':', 2 == '2', ':', 2 === '2', ':', strlen('é'), "\n";
echo overflow_add(9223372036854775807, 1), ':', overflow_add(1, 2) === 3, "\n";
echo overflow_sub(-9223372036854775807, 2), ':', overflow_sub(3, 2) === 1, "\n";
echo overflow_mul(9223372036854775807, 2), ':', overflow_mul(3, 2) === 6, "\n";
$side = 0;
if (true || ($side = 1)) { }
if (false && ($side = 2)) { }
echo $side, "\n";
$literal = [']', 'if', 'return', ')', 'while'];
echo count($literal), ':', $literal[0], ':', $literal[1], "\n";
$sum = 0;
for ($i = 0; $i < 4; $i = $i + 1) {
    if ($i === 1) { continue; }
    $sum = $sum + $i;
}
echo $sum, "\n";
function captured_argument($a, $other) { return $a[0]; }
$a = [1];
echo captured_argument($a, $a[0] = 2), ':', $a[0], "\n";
