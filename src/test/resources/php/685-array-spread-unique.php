<?php
// Array spread walks the source without a snapshot, and array_unique under SORT_STRING may run
// __toString during the walk; both must keep php's keys, order, by-value reads and references.
class Name
{
    public function __construct(private string $text)
    {
    }

    public function __toString(): string
    {
        return $this->text;
    }
}

$a = ['x' => 1, 2, 3, 'y' => 4];
$b = [10, 'x' => 20, 30];
$spread = [...$a, ...$b];
var_dump($spread);

$keys = [5 => 'a', 9 => 'b'];
$spreadKeys = [...$keys, ...$keys];
var_dump($spreadKeys);

$cell = 'ref';
$refs = [&$cell, 'plain'];
$spreadRefs = [...$refs];
$cell = 'changed';
var_dump($spreadRefs, $refs);

function yieldPairs(): Generator
{
    yield 'k' => 'v';
    yield 0 => 'z';
}
var_dump([...yieldPairs()]);
var_dump([1, ...[], 2, ...['a' => 3, 4]]);

// array_unique keeps the first key of each value, for packed and mixed arrays.
var_dump(array_unique(['a', 'b', 'a', 'c', 'b']));
var_dump(array_unique(['1', '01', '1.0', 1, 1.0, true, null, 'a', 'A']));
var_dump(array_unique(['a', 'A', 'b'], SORT_STRING | SORT_FLAG_CASE));
var_dump(array_unique([5 => 'a', 'x' => 'b', 7 => 'a', 'y' => 'b']));
var_dump(array_unique([], SORT_STRING));
var_dump(array_unique(['only'], SORT_STRING));

// Objects under SORT_STRING: __toString runs for each comparison key.
$first = new Name('dup');
$second = new Name('dup');
$third = new Name('other');
$unique = array_unique([$first, $second, $third], SORT_STRING);
echo 'unique objects=', count($unique), ' keys=', implode(',', array_keys($unique)), "\n";

// References stay references through array_unique's value copy.
$cell = 'ref';
$refs = [&$cell, 'x', &$cell];
$uniqueRefs = array_unique($refs);
$cell = 'later';
var_dump($uniqueRefs);
