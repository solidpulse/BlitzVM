<?php
declare(strict_types=1);

function show(string $label, mixed $value): void {
    echo $label, ': ';
    var_dump($value);
}

function strictInt(int $value): bool {
    return $value === 2;
}

function strictPositive(int $value): bool {
    return $value > 0;
}

// Accepted callback shape still works: the callback itself declares exact types.
show('strict-int', array_any([1, 2, 3], static fn (int $value): bool => $value === 2));
show('strict-all', array_all([1, 2, 3], static fn (int $value): bool => $value > 0));
show('strict-named', array_any([1, 2, 3], 'strictInt'));
show('strict-first-class', array_all([1, 2, 3], strictPositive(...)));

// Under strict_types a scalar type mismatch is a TypeError instead of a coercion.
foreach ([
    'closure-int' => static fn () => array_any(['2'], static fn (int $value): bool => true),
    'named-function' => static fn () => array_any(['2'], 'strictInt'),
    'first-class' => static fn () => array_all(['2'], strictPositive(...)),
    'return-type' => static fn () => array_any([1], static fn (int $value): string => $value),
] as $label => $run) {
    try {
        $run();
        show($label, 'no error');
    } catch (TypeError $e) {
        // The callee's name carries its source path, which differs between machines.
        $message = $e->getMessage();
        $at = strpos($message, '(): ');
        show($label, $at === false ? $message : substr($message, $at + 4));
    }
}

// The builtin's own argument checks do not depend on the caller's strict_types.
try {
    array_any('nope', static fn (mixed $value): bool => true);
} catch (TypeError $e) {
    show('non-array', $e->getMessage());
}

// Value and call_user_func paths still reach the builtin and the same callback.
show('value-call', ($fn = 'array_any')([1, 2], static fn (int $value): bool => $value === 2));
show('call-user-func', call_user_func('array_any', [1, 2], static fn (int $value): bool => $value === 2));
