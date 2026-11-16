<?php
$s = "Hello World 123\nabc";
foreach ([0, 1, 2, 3, 4] as $mode) {
    $r = count_chars($s, $mode);
    if (is_array($r)) {
        echo "mode $mode count=", count($r), "\n";
        echo implode(",", array_slice($r, 0, 6, true)), "\n";
        echo implode(",", array_slice($r, -3, 3, true)), "\n";
    } else {
        echo "mode $mode len=", strlen($r), " hex=", bin2hex($r), "\n";
    }
}
echo "mode1=", bin2hex(implode("", array_map("chr", array_keys(count_chars($s, 1))))), "\n";
echo "empty0=", count(count_chars("", 0)), " empty1=", count(count_chars("", 1)), "\n";
echo "empty3=", strlen(count_chars("", 3)), " empty4=", strlen(count_chars("", 4)), "\n";
echo "empty4hex=", bin2hex(count_chars("", 4)), "\n";
try {
    count_chars($s, 5);
} catch (Throwable $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}

// sscanf: the array form and the by-reference form.
var_dump(sscanf("age: 25 name: Bob", "age: %d name: %s"));
var_dump(sscanf("2024-05-06", "%4d-%2d-%2d"));
var_dump(sscanf("0x1f 0755 ff 777", "%i %i %x %o"));
var_dump(sscanf("3.5 2.5e3 nan", "%f %f %s"));
var_dump(sscanf("hello world", "%[a-z] %s"));
var_dump(sscanf("hello123", "%[^0-9]%d"));
var_dump(sscanf("skip 42", "%*s %d"));
var_dump(sscanf("abc", "%d"));
var_dump(sscanf("10", "%d %d"));
var_dump(sscanf("12-34", "%d/%d"));
var_dump(sscanf("ab", "%5c"));
var_dump(sscanf("42abc", "%d%s"));
var_dump(sscanf("50%", "%d%%"));
var_dump(sscanf("", "%d"));
var_dump(sscanf("abc", "%n"));
var_dump(sscanf("", ""));

$x = null;
$y = null;
var_dump(sscanf("10 20", "%d %d", $x, $y), $x, $y);
$x = 99;
var_dump(sscanf("abc", "%d", $x), $x);
$x = 99;
try {
    sscanf("abc", "%s%d", $x);
} catch (Throwable $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
var_dump($x);
try {
    sscanf("1 2 3", "%d %d %d", $x, $y);
} catch (Throwable $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
try {
    sscanf("abc", "%q");
} catch (Throwable $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
