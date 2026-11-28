<?php
// Packed int/float arrays through every storage transition: a write of another type boxes the
// array, references generalize it, and copy-on-write must keep both sides intact.

function dump(string $label, $value): void {
    echo $label, ":\n";
    var_dump($value);
}

$ints = [1, 2, 3];
dump('ints', $ints);
var_export($ints);
echo "\n";
echo serialize($ints), "\n";
echo json_encode($ints), "\n";
$same = [1, 2, 3];
var_dump($ints === $same);
$boxed = [1, 2, 3];
$boxed[3] = 'x';
unset($boxed[3]);
var_dump($boxed, $ints === $boxed);

$copy = $ints;
$copy[0] = 10;
dump('int copy original', $ints);
dump('int copy changed', $copy);

$a = [1, 2, 3];
$a[1] = 1.5;
dump('float into int', $a);
$b = [1.5, 2.5];
$b[0] = 2;
dump('int into float', $b);
$c = [1, 2];
$c[] = 'x';
$c[] = 3;
dump('string then int append', $c);
$d = [1.5];
$d[] = null;
$d[] = true;
$d[] = false;
$d[] = [4];
$d[] = new stdClass();
echo 'object class: ', get_class($d[count($d) - 1]), "\n";
dump('null bool array object into float', $d);

$f = [1, 2, 3];
$r = &$f[1];
$f[1] += 5;
dump('reference cell', $f);
var_dump($r);
$f[2] = 99;
var_dump($r);
unset($r);

$g = [1, 2, 3];
foreach ($g as &$v) {
    $v *= 10;
}
unset($v);
echo 'foreach by reference: ', implode(',', $g), "\n";
$sum = 0;
foreach ($g as $v) {
    $sum += $v;
}
var_dump($sum);

$h = [1, 2, 3, 4];
unset($h[1]);
$h[] = 5;
dump('unset middle then append', $h);
$i = [1, 2, 3];
unset($i[2]);
$i[] = 4;
dump('unset end then append', $i);

$j = [1, 2, 3];
$k = $j;
$k[0] = 's';
dump('generalized copy original', $j);
dump('generalized copy changed', $k);
$l = [1.5];
$m = $l;
$m[] = 2;
dump('float copy original', $l);
dump('float copy changed', $m);

$inner = [1, 2];
$outer = [$inner, 3];
$outer[0][0] = 9;
dump('nested original', $inner);
dump('nested outer', $outer);

$n = [1, 2];
$n[5] = 3;
$n[] = 4;
dump('key beyond size', $n);
$o = [-2 => 'a', -1 => 'b'];
dump('negative keys', $o);
$o[] = 'c';
dump('negative keys append', $o);

$p = [PHP_INT_MAX, PHP_INT_MIN];
$p[0] += 1;
$p[1] -= 1;
dump('int overflow', $p);
$q = [PHP_INT_MAX];
$q[] = PHP_INT_MIN;
dump('int extremes', $q);

$df = [1.5, 2.5];
$df[0] += 2;
$df[1] *= 2;
dump('float compound', $df);
$expr = [1, 2, 3];
$assigned = ($expr[0] += 5);
dump('compound value', $expr);
var_dump($assigned);

$floats = [1.5, 2.5];
dump('floats', $floats);
var_export($floats);
echo "\n";
echo serialize($floats), "\n";
echo json_encode($floats), "\n";
$special = [INF, NAN, -0.0];
dump('special floats', $special);
var_export($special);
echo "\n";
echo serialize($special), "\n";
echo json_encode($special, JSON_PARTIAL_OUTPUT_ON_ERROR), "\n";
