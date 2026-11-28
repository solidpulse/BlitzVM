<?php
// By value the loop walks the array as it was before the first element.
$items = [1, 2, 3];
foreach ($items as $value) {
    echo $value;
    if ($value === 1) {
        $items[] = 4;
        $items[0] = 9;
        unset($items[1]);
    }
}
echo ' ', json_encode($items), "\n";

// Holes keep their positions and keys.
$holes = [0 => 'a', 2 => 'b', 3 => 'c'];
foreach ($holes as $key => $value) {
    echo $key, ':', $value, ' ';
}
echo "\n";

// Nested arrays and destructuring of each element.
$matrix = [[1, 2], [3, 4]];
foreach ($matrix as [$a, $b]) {
    echo $a + $b, ' ';
}
echo "\n";

// Keys that look numeric but are strings stay strings.
$mixed = ['1' => 'one', 2 => 'two', '03' => 'three'];
foreach ($mixed as $key => $value) {
    echo gettype($key), ':', $key, '=', $value, ' ';
}
echo "\n";

// An ArrayIterator is Traversable: its own methods drive the loop.
$iterator = new ArrayIterator(['x' => 1, 'y' => 2]);
foreach ($iterator as $key => $value) {
    echo $key, '=', $value, ' ';
    if ($key === 'x') {
        $iterator['z'] = 3;
    }
}
echo "\n";

// A plain object iterates its visible properties.
$object = new stdClass();
$object->a = 1;
$object->b = 2;
$object->c = 3;
foreach ($object as $key => $value) {
    echo $key, '=', $value, ' ';
}
echo "\n";

// A generator is also a Traversable.
function numbers(): Generator
{
    yield 'first' => 1;
    yield 'second' => 2;
    yield 3;
}
foreach (numbers() as $key => $value) {
    echo $key, '=', $value, ' ';
}
echo "\n";

// An empty loop still runs its start and end.
$count = 0;
foreach ([] as $value) {
    $count++;
}
echo $count, "\n";

// foreach over an already-consumed generator ends immediately.
$generator = numbers();
foreach ($generator as $value) {
    if ($value === 1) {
        break;
    }
}
$seen = 0;
foreach ($generator as $value) {
    $seen++;
}
echo $seen, "\n";
