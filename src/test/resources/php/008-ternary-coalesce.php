<?php
$a = 10;
$b = 0;
$arr = ["defined" => 42];

echo ($a > 5) ? "greater\n" : "smaller\n";
echo ($b ?: "fallback") . "\n";
echo ($a ?: "fallback") . "\n";

echo ($arr["defined"] ?? 100) . "\n";
echo ($arr["missing"] ?? 100) . "\n";
echo ($missing ?? $arr["missing"] ?? "default") . "\n";
