<?php
// A by-reference loop writes through to the array.
$numbers = [1, 2, 3];
foreach ($numbers as &$number) {
    $number *= 2;
}
unset($number);
echo json_encode($numbers), "\n";

// Unset future elements are skipped.
$changing = [1, 2, 3, 4];
$seen = [];
foreach ($changing as $key => &$value) {
    if ($key === 0) {
        unset($changing[2]);
    }
    $seen[] = $key;
    $value = $value * 10;
}
unset($value);
echo json_encode($seen), ' ', json_encode($changing), "\n";

// Nested by-reference loops.
$rows = [[1, 2], [3, 4]];
foreach ($rows as &$row) {
    foreach ($row as &$cell) {
        $cell++;
    }
    unset($cell);
}
unset($row);
echo json_encode($rows), "\n";

// A by-reference loop over string keys keeps them.
$map = ['a' => 1, 'b' => 2];
foreach ($map as $key => &$value) {
    $value = $key . $value;
}
unset($value);
echo json_encode($map), "\n";

// A by-value loop over the same array sees the writes of a previous by-reference loop.
$shared = [1, 2, 3];
foreach ($shared as &$item) {
    $item += 10;
}
unset($item);
$total = 0;
foreach ($shared as $item) {
    $total += $item;
}
echo $total, "\n";

// A reference element in the array is unwrapped by value but kept by reference.
$cell = 5;
$withReference = [&$cell, 6];
foreach ($withReference as $item) {
    echo $item, ' ';
}
echo "\n";
foreach ($withReference as &$item) {
    $item *= 3;
}
unset($item);
echo $cell, ' ', json_encode($withReference), "\n";
