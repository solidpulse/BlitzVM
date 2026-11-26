<?php
declare(strict_types=1);

function show(string $label, mixed $value): void {
    echo $label, ': ';
    var_dump($value);
}

show('upper', strtoupper('Hello'));
show('upper-bytes', strtoupper("caf\xC3\xA9"));
show('upper-empty', strtoupper(''));
show('named', strtoupper(string: 'abc'));

// Under strict_types strtoupper rejects an int or null instead of converting it.
foreach ([
    'upper-int' => static fn () => strtoupper(123),
    'upper-null' => static fn () => strtoupper(null),
] as $label => $run) {
    try {
        $run();
        show($label, 'no error');
    } catch (TypeError $e) {
        show($label, $e->getMessage());
    }
}

foreach (['urlencode', 'rawurlencode', 'urldecode', 'rawurldecode'] as $fn) {
    show($fn . '-space', $fn('a b'));
    show($fn . '-percent', $fn('%41%2f%zz'));
    show($fn . '-empty', $fn(''));
}

show('named-url', rawurldecode(string: 'a%20b'));
$decode = 'rawurldecode';
show('value-url', $decode('a%20b'));
show('cuf', call_user_func('urlencode', 'a b'));

foreach ([
    'upper-no-arg' => static fn () => strtoupper(),
    'upper-too-many' => static fn () => strtoupper('a', 'b'),
    'url-no-arg' => static fn () => rawurldecode(),
] as $label => $run) {
    try {
        $run();
        show($label, 'no error');
    } catch (Error $e) {
        show($label, $e->getMessage());
    }
}
