<?php
declare(strict_types=1);

function show(string $label, mixed $value): void {
    echo $label, ': ';
    var_dump($value);
}

$a = [1, 2, 3];
show('values', array_values($a));
show('pop', array_pop($a));
show('after', $a);
show('current', current($a));

$assoc = ['x' => 1, 'y' => 2];
show('assoc-values', array_values($assoc));
show('assoc-pop', array_pop($assoc));
show('assoc-after', $assoc);

$copy = [1, 2, 3];
$keep = $copy;
show('cow-pop', array_pop($copy));
show('cow-copy', $keep);

$cell = 1;
$refs = [&$cell, 2];
show('ref-pop', array_pop($refs));
show('ref-cell', $cell);

show('named-values', array_values(array: [1, 2]));
$named = [1, 2];
show('named-pop', array_pop(array: $named));
$fn = 'array_values';
show('value-values', $fn([1, 2]));
show('cuf-values', call_user_func('array_values', [1, 2]));

// The array parameter has no scalar coercion in either mode.
foreach ([
    'values-string' => static fn () => array_values('x'),
    'values-int' => static fn () => array_values(5),
    'values-null' => static fn () => array_values(null),
    'values-too-many' => static fn () => array_values([1], 2),
    'pop-literal' => static fn () => array_pop([1, 2]),
] as $label => $run) {
    try {
        $run();
        show($label, 'no error');
    } catch (Error $e) {
        show($label, $e->getMessage());
    }
}
