<?php

function show(string $label, mixed $value): void {
    echo $label, ': ';
    var_dump($value);
}

// array_values returns a fresh list of the values, dropping the keys.
$list = [1, 2, 3];
$assoc = ['x' => 1, 'y' => 2];
$gapped = [];
$gapped[5] = 'five';
$gapped['k'] = 'key';
show('values-list', array_values($list));
show('values-assoc', array_values($assoc));
show('values-gapped', array_values($gapped));
show('values-empty', array_values([]));
$cell = 1;
show('values-ref-value', array_values([&$cell, 2])[0]);
show('values-nested', array_values([[1], [2 => 3]]));
show('values-original', $list);

// array_pop removes the last element, returning it and resetting the pointer.
$popped = [1, 2, 3];
show('pop', array_pop($popped));
show('pop-after', $popped);
show('pop-current', current($popped));
show('pop-key', key($popped));
$assocPop = ['a' => 1, 'b' => 2];
show('pop-assoc', array_pop($assocPop));
show('pop-assoc-after', $assocPop);
$empty = [];
show('pop-empty', array_pop($empty));
show('pop-empty-after', $empty);

// A value copy keeps its own array; the moved array is the one that changes.
$original = [1, 2, 3];
$copy = $original;
show('pop-cow', array_pop($original));
show('pop-cow-original', $original);
show('pop-cow-copy', $copy);

// Reference cells are read through and the cell itself stays a reference.
$number = 1;
$withRef = [&$number, 2];
show('pop-ref', array_pop($withRef));
show('pop-ref-cell', $number);

// An alias sees the pop, as with any in-place array write.
$aliasTarget = [1, 2, 3];
$alias = &$aliasTarget;
show('pop-alias', array_pop($alias));
show('pop-alias-target', $aliasTarget);

// Named arguments and the value paths.
$named = [1, 2, 3];
show('named-values', array_values(array: $named));
show('named-pop', array_pop(array: $named));
$f = 'array_values';
show('value-values', $f([1, 2]));
$g = 'array_pop';
show('value-pop', @$g($named));

// Wrong shapes keep the builtin's errors.
foreach ([
    'values-string' => static fn () => array_values('x'),
    'values-null' => static fn () => array_values(null),
    'values-no-arg' => static fn () => array_values(),
    'values-too-many' => static fn () => array_values([1], 2),
    'pop-no-arg' => static fn () => array_pop(),
    'pop-too-many' => static function () {
        $array = [1];
        return array_pop($array, 1);
    },
    'pop-literal' => static fn () => array_pop([1, 2]),
] as $label => $run) {
    try {
        $run();
        show($label, 'no error');
    } catch (Error $e) {
        show($label, $e->getMessage());
    }
}
