<?php
echo "5" + 3, "\n";
echo "abc" . 1, "\n";

echo 0.1 + 0.2, "\n";
echo 10 / 4, "\n";
echo 7 / 7, "\n";

var_dump(is_int(42));
var_dump(is_int("42"));
var_dump(is_string("hello"));
var_dump(is_string(123));
var_dump(is_array([1, 2]));
var_dump(is_array("not array"));

echo gettype(42), "\n";
echo gettype(3.14), "\n";
echo gettype("str"), "\n";
echo gettype(true), "\n";
echo gettype(null), "\n";
echo gettype([]), "\n";

echo intval("123"), "\n";
echo intval(45.67), "\n";

var_dump((int) "99");
var_dump((string) 123);
var_dump((float) "3.14");
var_dump((bool) 0);
var_dump((bool) 1);
