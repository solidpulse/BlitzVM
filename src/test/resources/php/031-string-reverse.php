<?php
function reverse_string($str) {
    $len = strlen($str);
    $reversed = "";
    for ($i = $len - 1; $i >= 0; $i--) {
        $reversed .= substr($str, $i, 1);
    }
    return $reversed;
}

$original = "Hello, World!";
echo "Original: ", $original, "\n";
echo "Reversed: ", reverse_string($original), "\n";
