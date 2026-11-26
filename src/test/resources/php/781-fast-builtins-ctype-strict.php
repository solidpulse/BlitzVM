<?php
declare(strict_types=1);

function show(string $label, mixed $value): void {
    echo $label, ': ';
    var_dump($value);
}

// String arguments take the same ASCII C-locale test under strict_types.
show('lower', ctype_lower('abc'));
show('lower-mixed', ctype_lower('aBc'));
show('lower-empty', ctype_lower(''));
show('upper', ctype_upper('ABC'));
show('digit', ctype_digit('0123'));
show('alpha-bytes', ctype_alpha("\xC3\xA9"));
show('space', ctype_space(" \t\n"));
show('punct', ctype_punct('!?'));
show('graph', ctype_graph('abc'));
show('print', ctype_print('a b'));
show('cntrl', ctype_cntrl("\x01"));
show('xdigit', ctype_xdigit('0aF'));
show('alnum', ctype_alnum('a1'));

// An int still reaches the builtin's deprecation path.
set_error_handler(static function (int $errno, string $message): bool {
    echo "deprecated: $errno: $message\n";
    return true;
});
show('digit-int', ctype_digit(123));
restore_error_handler();

show('named', ctype_upper(text: 'ABC'));
$fn = 'ctype_lower';
show('value', $fn('abc'));
show('call-user-func', call_user_func('ctype_digit', '123'));

foreach ([
    'no-arg' => static fn () => ctype_lower(),
    'too-many' => static fn () => ctype_lower('a', 'b'),
] as $label => $run) {
    try {
        $run();
        show($label, 'no error');
    } catch (Error $e) {
        show($label, $e->getMessage());
    }
}
