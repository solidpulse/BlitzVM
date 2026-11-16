<?php
function refs() {
    $a = 1; $old = &$a;
    $input = ['a' => 5, 'b' => 6];
    $copy = $input;
    echo extract($input, EXTR_REFS), "\n";
    $a = 8; $input['b'] = 9;
    echo "$a:$b:$old:", json_encode($input), ':', json_encode($copy), "\n";
    unset($a); $input['a'] = 10;
    echo isset($a) ? 'set' : 'unset', ':', $input['a'], "\n";
}
refs();
$input = ['top' => 12];
extract($input, EXTR_REFS);
$top++;
echo $input['top'], "\n";
