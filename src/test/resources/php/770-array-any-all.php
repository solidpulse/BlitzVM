<?php

function show(string $label, mixed $value): void {
    echo $label, ': ';
    var_dump($value);
}

function isTwo(mixed $value): bool {
    return $value === 2;
}

// The closure callback shape the fast path answers: value and key, early exit.
$list = [1, 2, 3, 4];
$assoc = ['a' => 1, 'b' => 2, 'c' => 3];
$seen = [];
show('any-list', array_any($list, static function (mixed $value, int|string $key) use (&$seen): bool {
    $seen[] = [$key, $value];
    return $value === 3;
}));
show('any-seen', $seen);
show('any-miss', array_any($list, static fn (mixed $value): bool => $value === 9));
show('all-hit', array_all($list, static fn (mixed $value): bool => $value < 5));
$seen = [];
show('all-assoc-miss', array_all($assoc, static function (mixed $value, int|string $key) use (&$seen): bool {
    $seen[] = [$key, $value];
    return $value < 2;
}));
show('all-seen', $seen);
show('any-empty', array_any([], static fn (mixed $value): bool => true));
show('all-empty', array_all([], static fn (mixed $value): bool => false));

// Truthiness of the callback's answer, not identity with true.
show('any-truthy', array_any([0, 1], static fn (mixed $value): mixed => $value));
show('all-truthy', array_all([1, 'x', [0]], static fn (mixed $value): mixed => $value));

// Keys can be strings or gaps; the walk visits them in array order.
$gapped = [];
$gapped[5] = 'five';
$gapped[2] = 'two';
$gapped['k'] = 'key';
show('any-gapped-key', array_any($gapped, static fn (mixed $value, int|string $key): bool => $key === 'k'));
show('all-gapped-value', array_all($gapped, static fn (mixed $value): bool => is_string($value)));

// A callback may change the array it walks; the builtin walks a snapshot.
$mutated = [1, 2, 3];
show('any-mutating', array_any($mutated, static function (mixed $value) use (&$mutated): bool {
    $mutated[] = $value * 10;
    return $value === 2;
}));
show('any-mutated', $mutated);

// A reference cell in the array is read through, like the builtin does.
$cell = 1;
$withRef = [&$cell, 5];
show('any-ref-cell', array_any($withRef, static fn (mixed $value): bool => $value === 1));
show('by-ref-cell', $cell);

// A callback that throws stops the walk.
try {
    array_any([1, 2], static function (mixed $value): bool {
        throw new RuntimeException('stop ' . $value);
    });
} catch (RuntimeException $e) {
    show('throwing', $e->getMessage());
}

// The shapes that must go back to the builtin: a non-closure callable, a non-array,
// and a callback that is not callable.
show('named-function', array_any([1, 2], 'isTwo'));
show('first-class-function', array_any([1, 2], isTwo(...)));
show('value-call', ($fn = 'array_any')([1, 2], static fn (mixed $value): bool => $value === 2));
show('call-user-func', call_user_func('array_any', [1, 2], static fn (mixed $value): bool => $value === 2));
show('call-user-func-array', call_user_func_array('array_any', [[1, 2], static fn (mixed $value): bool => $value === 2]));
show('named-args', array_any(array: [1, 2], callback: static fn (mixed $value): bool => $value === 2));
show('named-args-reordered', array_all(callback: static fn (mixed $value): bool => $value > 0, array: [1, 2]));

foreach ([
    'non-array' => static fn () => array_any('nope', static fn (mixed $value): bool => true),
    'non-array-all' => static fn () => array_all(null, static fn (mixed $value): bool => true),
] as $label => $run) {
    try {
        $run();
        show($label, 'no error');
    } catch (TypeError $e) {
        show($label, $e->getMessage());
    }
}

// An invalid callback throws an Error. Its wording is not part of this fixture.
foreach ([
    'not-callable' => static fn () => array_any([1], 'no_such_function'),
    'null-callback' => static fn () => array_any([1], null),
    'not-callable-all' => static fn () => array_all([1], 42),
] as $label => $run) {
    try {
        $run();
        show($label, 'no error');
    } catch (Error $e) {
        show($label, 'Error');
    }
}

// Wrong parameter type in a weak-mode closure: numeric strings coerce, others do not.
show('weak-coerce', array_any(['1', '2'], static fn (int $value): bool => $value === 2));
try {
    array_any(['x'], static fn (int $value): bool => true);
} catch (TypeError $e) {
    // A closure's name carries its source path, which differs between machines.
    show('weak-type-error', preg_replace('/\{closure:[^}]*\}/', '{closure}', $e->getMessage()));
}
