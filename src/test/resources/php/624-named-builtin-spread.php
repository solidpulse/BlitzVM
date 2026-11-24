<?php
// String-keyed spreads into builtins, and named forwarding through call_user_func.
$words = ['abc ', 'Def', 'abX', 'zz', 'Hello', 'ab', 'q', 'World'];
$s = 'Hello';

echo str_pad(...['string' => 'x', 'length' => 4]), "\n";
echo str_pad('x', ...['length' => 4]), "\n";
echo str_pad(...['x'], length: 4), "\n";
echo htmlentities(...['string' => '<a>', 'flags' => ENT_QUOTES]), "\n";
var_dump(array_slice($words, ...['offset' => 1, 'length' => 2, 'preserve_keys' => true]));
var_dump(json_decode(...['json' => '{"a":1}', 'depth' => 2, 'flags' => JSON_OBJECT_AS_ARRAY]));
var_dump(in_array(...['needle' => 'zz', 'haystack' => $words, 'strict' => true]));

echo call_user_func('str_pad', string: 'x', length: 3), "\n";
echo call_user_func(string: 'x', length: 3, callback: 'str_pad'), "\n";
echo call_user_func_array('str_pad', ['string' => 'x', 'length' => 3, 'pad_string' => '-']), "\n";
echo call_user_func_array('str_pad', ['length' => 4, 'string' => 'y']), "\n";
var_dump(call_user_func_array('array_slice', [$words, 'offset' => 1, 'length' => 2]));
var_dump(call_user_func_array('htmlentities', ['string' => '<a>', 'flags' => ENT_QUOTES]));

$a = [3, 1, 2];
var_dump(call_user_func_array('sort', ['array' => &$a]));
var_dump($a);

$c = null;
var_dump(call_user_func_array('str_replace', ['search' => 'l', 'replace' => 'L', 'subject' => $s, 'count' => &$c]));
var_dump($c);

$f = 'str_pad';
echo $f(string: 'z', length: 5, pad_string: '.'), "\n";
$g = 'str_contains';
var_dump($g(haystack: $s, needle: 'ell'));

$m = null;
var_dump(call_user_func_array('preg_match', ['pattern' => '/l/', 'subject' => $s, 'matches' => &$m]));
var_dump($m);

foreach ([
    fn() => str_pad(...['length' => 4], length: 5),
    fn() => call_user_func_array('str_pad', ['string' => 'x', 0 => 3]),
    fn() => call_user_func_array('str_pad', ['wat' => 1]),
] as $i => $call) {
    try {
        $call();
        echo $i, ": no error\n";
    } catch (Throwable $e) {
        echo $i, ': ', get_class($e), ': ', $e->getMessage(), "\n";
    }
}
