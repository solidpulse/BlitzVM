<?php
$arr = [1, 2, 3];
foreach ($arr as &$v) {
    $v *= 10;
}
unset($v);
echo "basic=" . implode(',', $arr) . "
";

// Classic PHP foreach-by-ref trap without unset($v)
$trap = [1, 2, 3];
foreach ($trap as &$tv) {
    $tv += 1;
}
foreach ($trap as $tv) {
}
unset($tv);
echo "trap=" . implode(',', $trap) . "
";

$assoc = ['a' => 1, 'b' => 2];
foreach ($assoc as $k => &$av) {
    $av = $k . ':' . ($av * 5);
}
unset($av);
echo "assoc=" . implode(',', $assoc) . "
";

class Bag {
    public $items = [2, 4, 6];
    public function doubleAll() {
        foreach ($this->items as &$item) {
            $item *= 2;
        }
        unset($item);
    }
}
$bag = new Bag();
$bag->doubleAll();
echo "prop=" . implode(',', $bag->items) . "
";

$matrix = [[1, 2], [3, 4]];
foreach ($matrix as &$row) {
    foreach ($row as &$cell) {
        $cell += 100;
    }
    unset($cell);
}
unset($row);
echo "matrix={$matrix[0][0]},{$matrix[0][1]},{$matrix[1][0]},{$matrix[1][1]}
";
