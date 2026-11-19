<?php
// Constant string keys at a read site are normalized once per site; the results must match
// normalizing on every access, including numeric strings and keys that stay strings.
$a = [123 => 'int', '0123' => 'lead0', '-0' => 'neg0', ' 1' => 'space', '1.5' => 'float',
      '9223372036854775808' => 'over', '-9223372036854775808' => 'min', 'name' => 'n', '' => 'empty',
      -5 => 'neg', 7 => null];
function read($a) {
    return [$a['123'], $a['0123'], $a['-0'], $a[' 1'], $a['1.5'], $a['9223372036854775808'],
            $a['-9223372036854775808'], $a['name'], $a[''], $a['-5']];
}
function probe($a) {
    return [isset($a['123']), isset($a['7']), isset($a['missing']), $a['7'] ?? 'dflt', $a['missing'] ?? 'dflt',
            isset($a['name']), $a['0123'] ?? 'x'];
}
// Many calls so the sites settle on their constant-key specializations, then on other arrays.
for ($i = 0; $i < 2000; $i++) { read($a); probe($a); }
var_dump(read($a), probe($a));
$b = ['123' => 'B', 'name' => 'bn', '-5' => 'b5'];
var_dump(array_keys($b));
function readSafe($a) { return [$a['123'] ?? '-', $a['name'] ?? '-', $a['-5'] ?? '-', $a['0123'] ?? '-']; }
for ($i = 0; $i < 2000; $i++) { readSafe($a); }
for ($i = 0; $i < 3; $i++) { var_dump(readSafe($b), probe($b)[0]); }
// A site that sees both a constant key and others.
function dyn($a, $k) { return $a[$k] ?? 'none'; }
foreach (['name', '123', 123, '0123', 'name', true, null, 1.7] as $k) { var_dump(dyn($a, $k)); }
// Missing constant key warns once per read with the key shown as php shows it.
function miss($a) { return $a['nope']; }
var_dump(miss($a));
function missNum($a) { return $a['42']; }
var_dump(missNum($a));
// Constant keys on a string and on an ArrayAccess object take the other paths.
$s = 'hello';
var_dump($s['1'] ?? 'none', isset($s['x']));
$o = new ArrayObject(['name' => 'obj']);
var_dump($o['name'], isset($o['name']), $o['zzz'] ?? 'none');
