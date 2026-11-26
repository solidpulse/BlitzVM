<?php

function show(string $label, mixed $value): void {
    echo $label, ': ';
    var_dump($value);
}

class Thing782 {
    public function __toString(): string {
        return 'a b/c';
    }
}

// strtoupper only folds ASCII letters, byte by byte.
show('upper', strtoupper('Hello World 123!'));
show('upper-bytes', strtoupper("caf\xC3\xA9"));
show('upper-empty', strtoupper(''));
show('upper-int', strtoupper(123));
show('upper-object', strtoupper(new Thing782()));
set_error_handler(static function (int $errno, string $message): bool {
    echo "deprecated: $errno: $message\n";
    return true;
});
show('upper-null', strtoupper(null));
restore_error_handler();

// urlencode/rawurlencode/urldecode/rawurldecode on their string shapes.
foreach (['urlencode', 'rawurlencode', 'urldecode', 'rawurldecode'] as $fn) {
    show($fn . '-space', $fn('a b'));
    show($fn . '-special', $fn('a&b=c?d/e%f+g~h'));
    show($fn . '-percent', $fn('%41%42%2f%zz%'));
    show($fn . '-plus', $fn('a+b'));
    show($fn . '-bytes', $fn("caf\xC3\xA9"));
    show($fn . '-empty', $fn(''));
    show($fn . '-int', $fn(123));
}
show('rawurlencode-object', rawurlencode(new Thing782()));

// Named arguments and the value paths.
show('named-upper', strtoupper(string: 'abc'));
show('named-url', rawurldecode(string: 'a%20b'));
$upper = 'strtoupper';
$decode = 'rawurldecode';
show('value-upper', $upper('abc'));
show('value-url', $decode('a%20b'));
show('cuf', call_user_func('urlencode', 'a b'));
show('cuf-array', call_user_func_array('rawurldecode', ['a%20b']));

// Argument counts go back to the builtin's own message.
foreach ([
    'upper-no-arg' => static fn () => strtoupper(),
    'upper-too-many' => static fn () => strtoupper('a', 'b'),
    'url-no-arg' => static fn () => rawurldecode(),
    'url-too-many' => static fn () => rawurldecode('a', 'b'),
    'unknown-named' => static fn () => urlencode(other: 'a'),
] as $label => $run) {
    try {
        $run();
        show($label, 'no error');
    } catch (Error $e) {
        show($label, $e->getMessage());
    }
}
