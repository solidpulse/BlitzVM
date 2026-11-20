<?php
// Typed variadic parameters check each element; by-reference parameters coerce the cell.
function strip($s) {
    return str_replace(__DIR__ . '/', '', $s);
}

set_error_handler(function (int $errno, string $message) {
    echo 'DEPRECATED: ', $message, "\n";
    return true;
});

function allInts(int ...$values) {
    echo 'ints ', implode(',', $values), "\n";
}
function allStrings(string ...$values) {
    echo 'strings ', implode('|', $values), "\n";
}
function bump(int &$value) {
    $value++;
    echo 'bump ', var_export($value, true), "\n";
}
function bumpAll(int &...$values) {
    foreach ($values as &$value) {
        $value += 10;
    }
    echo 'bumpAll ', implode(',', $values), "\n";
}
function nullable(?int &$value) {
    var_export($value);
    echo "\n";
}

allInts(1, '2', 3.0);
allInts();
allStrings(1, 2.5, true);
try { allInts(1, 'x'); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
try { allInts(1, 2.5); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }

$number = '41';
bump($number);
var_dump($number);

$first = '1';
$second = 2;
bumpAll($first, $second);
var_dump($first, $second);

$empty = null;
nullable($empty);
$text = '7';
nullable($text);
var_dump($text);
$arr = [];
try { nullable($arr); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
$bad = 'x';
try { bump($bad); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }