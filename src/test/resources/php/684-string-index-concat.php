<?php
// Reading one character of a string returns a one-character string; concatenating an empty
// string on the left keeps the right operand.
$s = 'hello';
echo $s[0], $s[4], $s[-1], $s[-5], "\n";
var_dump($s[1], $s[-2]);
var_dump($s[5] ?? 'missing');
$bytes = "\xC3\xA9t\xC3\xA9";
echo bin2hex($bytes[0]), ' ', bin2hex($bytes[1]), ' ', $bytes[2], "\n";
$number = '123';
var_dump($number['1'], @$number[true], @$number[1.9]);
$empty = '';
var_dump($empty[0] ?? 'none');

echo ''.'left', 'right'.'', ''.'both'.'', "\n";
$value = 'x';
echo '' . $value, $value . '', "\n";
var_dump('' . '', '' === '' . '');

$array = ['a' => 'first'];
echo $array['a'][0], $array['a'][4], "\n";
echo 'substr char=', substr('abc', 1, 1), "\n";

// The same one-character reads via foreach over characters.
$out = '';
foreach (str_split('abc') as $char) {
    $out .= $char . $char[0];
}
echo $out, "\n";
