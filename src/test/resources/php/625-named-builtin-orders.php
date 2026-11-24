<?php
// The benchmark builtins with parameters named in reverse order, and mixed positional/named.
$words = ['abc ', 'Def', 'abX', 'zz', 'Hello', 'ab', 'q', 'World'];
$s = 'Hello';

echo htmlentities(encoding: 'UTF-8', flags: ENT_QUOTES, string: '<a>', double_encode: true), "\n";
echo htmlentities('<a>', flags: ENT_QUOTES, encoding: 'UTF-8'), "\n";
echo str_pad(pad_type: STR_PAD_BOTH, pad_string: '-', length: 6, string: 'ab'), "\n";
echo str_pad('ab', 6, pad_type: STR_PAD_LEFT), "\n";
var_dump(base64_decode(strict: true, string: 'YWJj'));
var_dump(base64_decode('YWJj', strict: true));
var_dump(json_encode(flags: JSON_UNESCAPED_SLASHES, value: ['a' => 1.5]));
var_dump(json_encode(['a' => [1, 2]], depth: 4));
var_dump(array_slice(preserve_keys: true, length: 2, offset: 1, array: $words));
var_dump(array_slice($words, 1, preserve_keys: true));
var_dump(in_array(strict: true, haystack: $words, needle: 'zz'));
var_dump(in_array('zz', $words, strict: true));
var_dump(class_exists(autoload: false, class: 'stdClass'));
var_dump(class_exists('stdClass', autoload: false));
var_dump(explode(limit: 2, string: $words[4], separator: 'l'));
var_dump(explode('l', $words[4], limit: 2));
echo implode(array: $words, separator: '-'), "\n";
echo implode('-', $words), "\n";
echo mb_substr(encoding: 'UTF-8', length: 2, start: 1, string: $s), "\n";
echo mb_substr($s, 1, length: 2), "\n";
var_dump(array_filter(mode: ARRAY_FILTER_USE_KEY, callback: fn($k) => $k < 3, array: $words));
var_dump(array_filter($words, fn($k) => $k > 5, mode: ARRAY_FILTER_USE_KEY));
var_dump(preg_match(flags: PREG_OFFSET_CAPTURE, subject: $s, pattern: '/l/'));
var_dump(preg_match('/l/', $s, flags: PREG_OFFSET_CAPTURE));
var_dump(number_format(thousands_separator: '.', num: 1234.5, decimals: 1, decimal_separator: ','));
var_dump(number_format(1234.5, 2, thousands_separator: '_'));
var_dump(array_keys(strict: true, filter_value: 'zz', array: $words));
var_dump(array_keys($words, 'zz', strict: true));
var_dump(str_replace(subject: $words[0], replace: 'b', search: 'a'));
var_dump(str_replace('a', 'b', subject: $words[0]));
var_dump(round(precision: 2, num: 1.2345));
var_dump(round(1.2345, 2));
