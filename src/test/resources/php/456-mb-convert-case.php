<?php
var_dump(mb_convert_case('hello world', MB_CASE_TITLE));
var_dump(mb_convert_case('HELLO-WORLD', MB_CASE_TITLE));
var_dump(mb_convert_case('élan vital', MB_CASE_TITLE, 'UTF-8'));
var_dump(mb_convert_case('Hello World', MB_CASE_LOWER));
var_dump(mb_convert_case('Hello World', MB_CASE_UPPER));
var_dump(mb_convert_case('Hello World', MB_CASE_FOLD));

try {
    mb_convert_case('x', 99);
} catch (ValueError $e) {
    echo $e->getMessage(), "\n";
}

var_dump(MB_CASE_UPPER, MB_CASE_LOWER, MB_CASE_TITLE, MB_CASE_FOLD);
