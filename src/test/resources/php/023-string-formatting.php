<?php
$s1 = sprintf("Decimal: %d, String: %s, Hex: %x", 255, "items", 255);
echo $s1, "\n";

$s2 = sprintf("Float: %05.2f", 3.14159);
echo $s2, "\n";

echo number_format(1234567.89, 2, ".", ","), "\n";

echo str_pad("PHP", 8, "-"), "\n";
echo str_pad("42", 5, "0", 0), "\n";

$html = '<div>"Hello" & \'World\'</div>';
echo htmlspecialchars($html), "\n";

$data = ["name" => "Alice", "age" => 25, "active" => true];
echo json_encode($data), "\n";
echo json_encode("a string"), "\n";
echo json_encode(123), "\n";
