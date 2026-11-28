<?php

function show(string $label, mixed $value): void {
    echo $label, ': ';
    var_dump($value);
}

$tests = [
    'ctype_alnum' => ['abc123', 'ABC', '', 'a-b', ' '],
    'ctype_alpha' => ['abcXYZ', 'abc1', '', "\xC3\xA9"],
    'ctype_cntrl' => ["\x00\x01", "\t\n", '', 'a', "\x7F"],
    'ctype_digit' => ['0123', '', '12.3', '-1'],
    'ctype_graph' => ['abc!~', 'a b', "\t", ''],
    'ctype_lower' => ['abc', 'aBc', '123', '', "\xC3\xA9"],
    'ctype_print' => ['abc 123!', "\t", "\x7F", ''],
    'ctype_punct' => ['!@#', 'a!', ' ', ''],
    'ctype_space' => [" \t\n\r", 'a', '', ' '],
    'ctype_upper' => ['ABC', 'ABc', '123', ''],
    'ctype_xdigit' => ['0123abcdefABCDEF', 'g', '+1', ''],
];
foreach ($tests as $fn => $inputs) {
    foreach ($inputs as $index => $input) {
        show($fn . '-' . $index, $fn($input));
    }
}

// An int is deprecated and interpreted as one character or its digits, through the
// builtin, which the handler observes.
set_error_handler(static function (int $errno, string $message): bool {
    echo "deprecated: $errno: $message\n";
    return true;
});
show('digit-int-123', ctype_digit(123));
show('digit-int-negative', ctype_digit(-1));
show('digit-int-large', ctype_digit(300));
show('lower-int', ctype_lower(97));
restore_error_handler();

// Other non-string values are false, without a deprecation.
show('digit-null', ctype_digit(null));
show('digit-array', ctype_digit([]));
show('digit-object', ctype_digit(new stdClass()));

// Named arguments and the value paths.
show('named', ctype_lower(text: 'abc'));
$fn = 'ctype_alpha';
show('value', $fn('abc'));
show('value-upper', ($f = 'ctype_upper')('ABC'));
show('call-user-func', call_user_func('ctype_digit', '123'));

// Argument counts go back to the builtin's message.
foreach ([
    'no-arg' => static fn () => ctype_lower(),
    'too-many' => static fn () => ctype_lower('a', 'b'),
    'unknown-named' => static fn () => ctype_lower(other: 'a'),
] as $label => $run) {
    try {
        $run();
        show($label, 'no error');
    } catch (Error $e) {
        show($label, $e->getMessage());
    }
}
