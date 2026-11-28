<?php

function show(string $label, mixed $value): void {
    echo $label, ': ';
    var_dump($value);
}

// current/pos/key and the pointer moves over a list.
$a = [1, 2, 3];
show('current', current($a));
show('key', key($a));
show('next', next($a));
show('current-after-next', current($a));
show('key-after-next', key($a));
show('end', end($a));
show('key-at-end', key($a));
show('next-past-end', next($a));
show('key-past-end', key($a));
show('current-past-end', current($a));
show('prev-past-end', prev($a));
show('reset', reset($a));
show('key-after-reset', key($a));
show('prev-first', prev($a));
show('key-after-prev-first', key($a));
show('pos-empty-pointer', pos($a));

// Strings keys, gaps, and an empty array.
$assoc = ['a' => 1, 'b' => 2];
end($assoc);
show('assoc-key', key($assoc));
prev($assoc);
show('assoc-prev-key', key($assoc));
$gapped = [];
$gapped[5] = 'five';
$gapped[2] = 'two';
next($gapped);
show('gapped-key', key($gapped));
$empty = [];
show('empty-current', current($empty));
show('empty-key', key($empty));
show('empty-next', next($empty));
show('empty-reset', reset($empty));

// Moving the pointer writes the array, so a value copy keeps its own pointer.
$original = [1, 2, 3];
$copy = $original;
next($original);
show('cow-original', current($original));
show('cow-copy', current($copy));

// A reference cell in the array is read through, and an alias sees the move.
$cell = 1;
$withRef = [&$cell, 2];
next($withRef);
show('ref-value', current($withRef));
$alias = &$original;
reset($alias);
show('alias-move', current($original));

// Unsetting the element at the pointer leaves the pointer past the end.
$trimmed = [1, 2, 3];
next($trimmed);
unset($trimmed[1]);
show('unset-current', current($trimmed));
show('unset-key', key($trimmed));

// Named arguments bind to $array and use the same fast path.
$named = [1, 2, 3];
show('named-next', next(array: $named));
show('named-current', current(array: $named));
show('named-key', key(array: $named));
show('named-reset', reset(array: $named));

// The value and call_user_func paths still call the builtin.
$current = 'current';
$key = 'key';
show('value-current', $current($named));
show('value-key', $key($named));
show('cuf-current', call_user_func('current', $named));
show('cuf-reset', @call_user_func('reset', $named));

// Wrong types go back to the builtin, and a literal for a by-reference move is
// rejected before the fast path can see it.
foreach ([
    'current-string' => static fn () => current('x'),
    'key-null' => static fn () => key(null),
    'next-literal' => static fn () => next([1, 2]),
    'reset-string' => static function () {
        $value = 'x';
        return reset($value);
    },
] as $label => $run) {
    try {
        $run();
        show($label, 'no error');
    } catch (Error $e) {
        show($label, $e->getMessage());
    }
}
