<?php

$cnt = 0;
$res = preg_replace_callback('/\b([a-z]+)-(\d+)\b/', function ($m) {
    return strtoupper($m[1]) . '#' . ($m[2] * 2);
}, 'foo-3 bar-12 baz-7', 2, $cnt);
echo $res, '|cnt=', $cnt, "\n";

$res2 = preg_replace_callback(
    ['/\d+/', '/[a-z]+/'],
    fn($m) => '[' . $m[0] . ']',
    ['x' => 'a1b2', 'y' => '9c']
);
var_export($res2); echo "\n";

$cnt3 = 0;
$res3 = preg_replace_callback_array([
    '/\d+/' => fn($m) => '(' . ((int)$m[0] + 10) . ')',
    '/[A-Z]+/' => fn($m) => strtolower($m[0]),
], 'AB 5 CD 7', -1, $cnt3);
echo $res3, '|cnt3=', $cnt3, "\n";

$res4 = preg_replace_callback('/(a)(b)?/', function ($m) {
    return $m[0] . ':' . ($m[2] === null ? 'NULL' : $m[2]);
}, 'a ab', -1, $dummy, PREG_UNMATCHED_AS_NULL);
echo $res4, "\n";
