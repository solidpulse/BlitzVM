<?php
// substr: every sign combination of offset and length, out-of-range values, binary strings.
$strings = ['', 'a', 'abcdef', "a\0b", "\xC3\xA9x", "\x00\xFF\x80"];
$offsets = [-10, -6, -5, -1, 0, 1, 5, 6, 10, PHP_INT_MAX, PHP_INT_MIN];
$lengths = [null, -10, -6, -2, -1, 0, 1, 5, 6, 10, PHP_INT_MAX];

foreach ($strings as $si => $str) {
    foreach ($offsets as $offset) {
        foreach ($lengths as $li => $length) {
            $result = $length === null ? substr($str, $offset) : substr($str, $offset, $length);
            printf("%d %d L%d %s\n", $si, $offset, $li, bin2hex($result));
        }
    }
}

// Weak-mode coercion of numeric arguments.
var_dump(substr('abcdef', '2'), substr('abcdef', 2, '3'), substr('abcdef', 2.9),
    substr('abcdef', 2, 2.9), substr('abcdef', null), substr('abcdef', false),
    substr('abcdef', true), substr('abcdef', 1, null), substr('abcdef', 1, false));
var_dump(substr('abcdef', PHP_INT_MAX, PHP_INT_MAX), substr('abcdef', -PHP_INT_MAX),
    substr('abcdef', 2, -PHP_INT_MAX));
