<?php
$str = "  hello world  ";

echo strlen($str), "\n";
echo trim($str), "\n";
echo strtoupper("abc"), "\n";
echo strtolower("XYZ"), "\n";
echo ucfirst("php runtime"), "\n";
echo str_repeat("*-", 5), "\n";
echo substr("abcdef", 1, 3), "\n";
echo substr("abcdef", 2), "\n";

$pos = strpos("quick brown fox", "brown");
echo $pos, "\n";

$replaced = str_replace("world", "PHP", "hello world");
echo $replaced, "\n";
