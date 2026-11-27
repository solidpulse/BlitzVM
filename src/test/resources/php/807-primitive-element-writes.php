<?php
// Primitive element writes and compound writes: statement and expression forms, overflow,
// generalization, copy-on-write, append, negative keys, references and hot loops.

$a = [1, 2, 3];
$a[1] = 20;
var_dump($a);

$r = ($a[0] = 7);
var_dump($r, $a);
$r = ($a[1] += 3);
var_dump($r, $a);
$r = ($a[1] -= 1);
var_dump($r, $a);
$r = ($a[1] *= 2);
var_dump($r, $a);

$f = [1.5, 2.5, 3.5];
$f[0] += 1;
$f[1] *= 2.0;
$f[2] -= 0.5;
var_dump($f);
$r = ($f[0] += 0.25);
var_dump($r, $f);
$r = ($f[1] -= 1);
var_dump($r, $f);
$r = ($f[2] *= 2);
var_dump($r, $f);

// Overflow of an int compound becomes a float and generalizes the array.
$big = [PHP_INT_MAX, 1];
$big[0] += 1;
var_dump($big);
$big2 = [PHP_INT_MIN, 1];
$big2[0] -= 1;
var_dump($big2);
$big3 = [PHP_INT_MAX, 1];
$big3[0] *= 2;
var_dump($big3);

// A value of another type generalizes the array.
$w = [1, 2, 3];
$w[0] = 1.5;
var_dump($w);
$w2 = [1.5, 2.5];
$w2[0] = 3;
var_dump($w2);
$w3 = [1, 2];
$w3[0] = 's';
var_dump($w3);

// Copy-on-write: the shared source must not change.
$c = [1, 2, 3];
$d = $c;
$d[1] = 9;
var_dump($c, $d);
$cf = [1.5, 2.5];
$df = $cf;
$df[0] += 1.0;
var_dump($cf, $df);

// Appends take the general path, as do negative and missing keys.
$ap = [1, 2];
$ap[count($ap)] = 3;
$ap[] = 4;
$ap[-1] = 5;
var_dump($ap);

// Writing an index of an undefined variable creates the array.
$uv[0] = 1;
$uv[1] += 2;
var_dump($uv);

// A reference element keeps the array in object storage; later writes still see it.
$ref = [1, 2];
$x = 1;
$ref[1] =& $x;
$ref[0] = 9;
var_dump($ref);
$x = 5;
var_dump($ref);

// A few thousand compiled calls over typed arrays.
$h = array_fill(0, 100, 0);
for ($k = 0; $k < 2000; $k++) {
    for ($i = 0; $i < 100; $i++) {
        $h[$i] += $i;
    }
}
var_dump($h[0], $h[99]);

$hf = array_fill(0, 100, 1.0);
for ($k = 0; $k < 2000; $k++) {
    for ($i = 0; $i < 100; $i++) {
        $hf[$i] = $hf[$i] * 1.01 + $i;
    }
}
printf("%.6f %.6f\n", $hf[0], $hf[99]);
