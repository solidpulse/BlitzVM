<?php
$values = ['', 'a', 'abc', 'hello world', str_repeat('x', 17), str_repeat('x', 100),
    str_repeat('x', 200), str_repeat('x', 1000)];

foreach ($values as $value) {
    echo strlen($value), ' ', hash('xxh64', $value), ' ', hash('xxh128', $value), "\n";
}

var_dump(bin2hex(hash('xxh64', 'abc', true)));
var_dump(bin2hex(hash('xxh128', 'abc', true)));
var_dump(in_array('xxh64', hash_algos(), true), in_array('xxh128', hash_algos(), true));
