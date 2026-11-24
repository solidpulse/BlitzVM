<?php
// implode: single element, empty array, nested arrays, objects with __toString that modify
// the array being joined, floats, bools, nulls, negative zero, string glue and integer glue.
class Bag
{
    public array $items = [];
}

class Joiner
{
    public function __construct(private Bag $bag, private string $text)
    {
    }

    public function __toString(): string
    {
        $this->bag->items[] = 'added';
        $this->bag->items[0] = 'changed';
        return $this->text;
    }
}

echo 'plain=', implode('-', [1, 2, 3]), "\n";
echo 'spaces=', implode(' ', ['a', 'b', 'c']), "\n";
echo 'single=', implode(',', ['only']), "\n";
echo 'single-empty=', implode(',', ['']), '|', "\n";
echo 'empty=', implode(',', []), '|', "\n";
echo 'nested=', @implode(',', [[1, 2], [], 'x']), "\n";
echo 'nulls=', implode(',', [null, false, true, 0, 0.0, -0.0, 1.5, 'end']), "\n";
echo 'int-glue=', implode(0, ['a', 'b']), "\n";
echo 'glue-only=', implode('--', ['']), '|', "\n";

$bag = new Bag();
$bag->items = ['first', new Joiner($bag, 'second'), 'third'];
$joined = implode('|', $bag->items);
echo 'object=', $joined, ' items=', json_encode($bag->items), "\n";

$bag = new Bag();
$bag->items = [new Joiner($bag, 'only')];
echo 'object-single=', implode('|', $bag->items), ' items=', json_encode($bag->items), "\n";

$cell = 'ref';
$refs = [&$cell, 'plain', &$cell];
echo 'refs=', implode(':', $refs), "\n";
$cell = 'now';
echo 'refs-after=', implode(':', $refs), "\n";

// Single argument form, and one element that is not contiguous.
$sparse = [5 => 'five'];
echo 'sparse-single=', implode($sparse), "\n";
$assoc = ['a' => 1, 'b' => 2];
echo 'assoc=', implode(',', $assoc), "\n";
