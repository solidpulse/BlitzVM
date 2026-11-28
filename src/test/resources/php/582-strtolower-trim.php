<?php
// strtolower and the trim family: ASCII-only case mapping, bytes >= 0x80, custom character
// lists and a..z ranges.
$strings = [
    '', 'ABC', 'aBc', 'Hello World', "MiXeD\x00Tail", "\xC3\x80BC", "caf\xC3\xA9",
    "\x80\x81\xFE\xFF", "  padded  ", "\t\r\n x \n\r\t", "\xC2\xA0nbsp\xC2\xA0",
    "..dots..", "a-zA-Z-", "0123456789",
];

foreach ($strings as $str) {
    echo 'lower[', bin2hex($str), ']=', bin2hex(strtolower($str)), "\n";
}

$cases = [
    ['  abc  ', null],
    ["\t\r\n\0\x0B abc \x0B\0\n\r\t", null],
    ['..abc..', '.'],
    ['aabbccabc', 'a..c'],
    ['ABCXYZ', 'A..C'],
    ['xyzabcXYZ', 'a..z'],
    ['123abc321', '0..9'],
    ['xyzabc', 'x..z'],
    ['-abc-', '-'],
    ['abc', ''],
    ['aaa', 'a..a'],
    ["\xC2\xA0abc\xC2\xA0", "\xC2\xA0"],
    ['a-z-foo-a-z', 'a..z'],
    ['0-9abc9-0', '0..9'],
    ["\x00\x01abc\x01\x00", "\x00..\x02"],
    ['..a..b..', '.'],
];
foreach ($cases as $i => $case) {
    [$str, $chars] = $case;
    $trim = $chars === null ? trim($str) : trim($str, $chars);
    $left = $chars === null ? ltrim($str) : ltrim($str, $chars);
    $right = $chars === null ? rtrim($str) : rtrim($str, $chars);
    printf("%d trim=%s ltrim=%s rtrim=%s\n", $i, bin2hex($trim), bin2hex($left), bin2hex($right));
}

// The four trim spellings and the default character list.
var_dump(trim(" \t\n\r\0\x0Babc\x0B\0\r\n\t "), trim("xxabcxx", 'x'), rtrim("abcxyz", 'x..z'),
    ltrim("xyzabc", 'x..z'), chop("abc   "), chop("abcxxx", 'x'));
