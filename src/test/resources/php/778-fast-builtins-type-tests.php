<?php

function show(string $label, mixed $value): void {
    echo $label, ': ';
    var_dump($value);
}

class Thing {}

$values = [
    'array' => [1, 2],
    'string' => 'text',
    'int' => 42,
    'float' => 1.5,
    'true' => true,
    'false' => false,
    'null' => null,
    'object' => new Thing(),
];
$resource = fopen('php://memory', 'r');
$values['resource'] = $resource;

$tests = ['is_array', 'is_string', 'is_int', 'is_integer', 'is_long', 'is_float',
          'is_double', 'is_bool', 'is_null', 'is_object', 'is_scalar'];
foreach ($tests as $test) {
    foreach ($values as $label => $value) {
        show($test . '-' . $label, $test($value));
    }
}
fclose($resource);

// The one-argument shape also binds through named arguments.
show('named-array', is_array(value: [1]));
show('named-int', is_int(value: '1'));

// Wrong argument counts go back to the builtin's own message.
foreach ([
    'no-arg' => static fn () => is_array(),
    'too-many' => static fn () => is_array(1, 2),
    'unknown-named' => static fn () => is_null(other: 1),
] as $label => $run) {
    try {
        $run();
        show($label, 'no error');
    } catch (Error $e) {
        show($label, $e->getMessage());
    }
}

// A string callable that names a builtin is resolved once at the site.
function viaString(string $fn, mixed $value): bool {
    return $fn($value);
}
show('repeat-array', viaString('is_array', [1]));
show('repeat-string', viaString('is_string', 'x'));
show('repeat-null', viaString('is_null', null));

// One site that changes the builtin name exercises the generic fallback.
$names = ['is_array', 'is_string', 'is_int', 'is_null'];
$inputs = [[1], 'x', 5, null];
foreach ($names as $i => $fn) {
    show('polymorphic-' . $fn, $fn($inputs[$i]));
}

// A site that changes between a builtin and a user function keeps both correct.
function userFn(mixed $value): string {
    return 'user';
}
$callables = ['is_array', 'userFn', 'is_array'];
foreach ($callables as $fn) {
    show('mixed-' . $fn, $fn([1]));
}

// A leading backslash and a different case still name the builtin.
$slashed = '\IS_ARRAY';
show('slashed', $slashed([1]));
$upper = 'IS_ARRAY';
show('upper', $upper([1]));

// Static method strings and first-class callables keep their own paths.
class Holder {
    public static function check(mixed $value): string {
        return 'static';
    }
}
$static = 'Holder::check';
show('static-method', $static(1));
$firstClass = is_array(...);
show('first-class', $firstClass([1]));
$firstClassString = is_string(...);
show('first-class-string', $firstClassString('x'));

// call_user_func still reaches the builtin itself.
show('cuf', call_user_func('is_array', [1]));
show('cuf-array', call_user_func_array('is_string', ['x']));

// A missing function remains an Error; only its class is part of the fixture.
$missing = 'no_such_function_778';
try {
    $missing(1);
} catch (Error $e) {
    show('missing', 'Error');
}
