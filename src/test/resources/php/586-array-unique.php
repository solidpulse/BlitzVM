<?php
// array_unique: every sort flag, mixed int/string keys, numeric strings, natural order,
// case folding, objects and NAN.
class UniqueStringable {
    public $tag;
    public function __construct($tag) { $this->tag = $tag; }
    public function __toString(): string { return $this->tag; }
}

function show($label, $array, $flag = null) {
    $result = $flag === null ? array_unique($array) : array_unique($array, $flag);
    echo $label, '=';
    foreach ($result as $key => $value) {
        $type = gettype($key)[0] . ':' . $key . '=';
        if ($value instanceof UniqueStringable) {
            echo $type, 'obj(', $value->tag, ') ';
        } elseif (is_array($value)) {
            echo $type, 'array(', json_encode($value), ') ';
        } elseif (is_float($value) && is_nan($value)) {
            echo $type, 'NAN ';
        } else {
            echo $type, bin2hex((string) $value), ' ';
        }
    }
    echo "\n";
}

$mixed = ['1', '01', '1.0', 1, 1.0, true, null, 'a', 'A', '', 0, false, '0'];
show('regular', $mixed, SORT_REGULAR);
show('numeric', $mixed, SORT_NUMERIC);
show('string', $mixed, SORT_STRING);
show('string-explicit', $mixed, SORT_STRING);
show('locale', $mixed, SORT_LOCALE_STRING);
show('natural', $mixed, SORT_NATURAL);
show('string-case', $mixed, SORT_STRING | SORT_FLAG_CASE);
show('natural-case', $mixed, SORT_NATURAL | SORT_FLAG_CASE);
show('default', $mixed);

show('keys', [5 => 'a', 'x' => 'b', 7 => 'a', 'y' => 'b', 9 => 'c']);
show('keys-regular', [5 => 'a', 'x' => 'b', 7 => 'a', 'y' => 'b', 9 => 'c'], SORT_REGULAR);
show('numeric-strings', ['10', '1e1', '10.0', 10, '10.5', 10.5], SORT_NUMERIC);
show('floats', [1.0, 1.5, 0.1, '0.1', -0.0, 0.0], SORT_STRING);
show('booleans', [true, 1, '1', false, 0, '', null], SORT_STRING);
show('nested-regular', [[1, 2], [1, 2], [2, 1], ['1', '2']], SORT_REGULAR);
show('nested-numeric', [[1], [0], [], [2, 3]], SORT_NUMERIC);
show('objects-string', [new UniqueStringable('x'), new UniqueStringable('x'), new UniqueStringable('y')], SORT_STRING);
show('objects-case', [new UniqueStringable('X'), new UniqueStringable('x'), new UniqueStringable('y')], SORT_FLAG_CASE | SORT_STRING);
show('natural-files', ['img12', 'img10', 'img2', 'img02', 'img001', 'img1', 'img01']);
show('natural-case', ['Img1', 'img1', 'IMG1', 'Img2', 'img02'], SORT_NATURAL | SORT_FLAG_CASE);
show('nan-regular', [NAN, NAN, 0.0, -0.0], SORT_REGULAR);
show('nan-numeric', [NAN, NAN], SORT_NUMERIC);
show('empty', []);
show('single', ['only']);
show('case-always', ['a', 'A'], SORT_FLAG_CASE | SORT_STRING);
show('locale-case', ['a', 'A'], SORT_LOCALE_STRING | SORT_FLAG_CASE);
show('big-ints', [9007199254740992, 9007199254740993, '9007199254740992'], SORT_NUMERIC);
