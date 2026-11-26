<?php
declare(strict_types=1);

function show(string $label, mixed $value): void {
    echo $label, ': ';
    var_dump($value);
}

// The type tests accept every value and coerce nothing, so strict_types changes none
// of their answers.
$values = [[1], 'text', 42, 1.5, true, false, null, new stdClass()];
foreach (['is_array', 'is_string', 'is_int', 'is_float', 'is_bool', 'is_null',
          'is_object', 'is_scalar'] as $test) {
    $answers = [];
    foreach ($values as $value) {
        $answers[] = $test($value) ? '1' : '0';
    }
    show($test, implode('', $answers));
}

show('named', is_int(value: 42));
show('first-class', (is_array(...))([1]));

function viaString(string $fn, mixed $value): bool {
    return $fn($value);
}
show('repeat', viaString('is_array', [1]));
show('polymorphic', viaString('is_string', 'x'));

// A user function at the same kind of site keeps strict parameter checking.
function takeInt(int $value): bool {
    return true;
}
$user = 'takeInt';
try {
    $user('1');
    show('user-strict', 'no error');
} catch (TypeError $e) {
    show('user-strict', str_replace(__FILE__, basename(__FILE__), $e->getMessage()));
}

$missing = 'no_such_function_779';
try {
    $missing(1);
} catch (Error $e) {
    show('missing', 'Error');
}

show('cuf', call_user_func('is_array', [1]));
