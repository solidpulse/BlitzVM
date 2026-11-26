<?php
declare(strict_types=1);

function show(string $label, mixed $value): void {
    echo $label, ': ';
    var_dump($value);
}

// The array parameter has no scalar coercion, so strict_types changes nothing here.
$a = ['x' => 1, 'y' => 2, 'z' => 3];
show('current', current($a));
show('key', key($a));
show('next', next($a));
show('current-next', current($a));
show('end', end($a));
show('key-end', key($a));
show('reset', reset($a));
show('pos', pos($a));

show('named-next', next(array: $a));
show('value-current', ($f = 'current')($a));
show('cuf-reset', @call_user_func('reset', $a));

foreach ([
    'current-string' => static fn () => current('x'),
    'key-float' => static fn () => key(1.5),
    'next-literal' => static fn () => next([1, 2]),
] as $label => $run) {
    try {
        $run();
        show($label, 'no error');
    } catch (Error $e) {
        show($label, $e->getMessage());
    }
}
