<?php
// A local that is appended to many times stays an append buffer until it is read.
$text = '';
for ($i = 0; $i < 100; $i++) {
    $text .= 'ab';
}
echo strlen($text), ' ', substr($text, 0, 4), ' ', substr($text, -2), "\n";

// Reading through a reference flattens the buffer that lives in the cell.
$alias = &$text;
echo strlen($alias), "\n";
$alias .= 'cd';
echo strlen($text), ' ', substr($text, -4), "\n";

// A by-reference parameter writes through the caller's cell.
function appendMark(string &$value): void
{
    $value .= '!';
}
$marked = 'start';
appendMark($marked);
appendMark($marked);
echo $marked, "\n";

// Array elements and object properties that hold append buffers.
$array = ['key' => 'a'];
$array['key'] .= 'b';
$array['key'] .= 'c';
echo $array['key'], "\n";

$object = new stdClass();
$object->value = 'x';
$object->value .= 'y';
$object->value .= 'z';
echo $object->value, "\n";

// A reference to an array element, appended through the alias.
$holder = ['item' => 'one'];
$cell = &$holder['item'];
$cell .= '-two';
$cell .= '-three';
echo $holder['item'], ' ', $cell, "\n";

// Concatenation of a buffer with a prefix and a suffix.
$prefix = '>';
$suffix = '<';
echo $prefix . $text . $suffix === '>' . $text . '<' ? 'same' : 'different', "\n";
