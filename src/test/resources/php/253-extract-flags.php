<?php
function flags($flag, $prefix = '') {
    $a = 'old';
    $nil = null;
    $input = ['a' => 1, 'b' => 2, 'nil' => 3, 5 => 4, '' => 5, 'bad-x' => 6];
    echo $flag, ':', extract($input, $flag, $prefix), ':', json_encode([
        $a, $b ?? null, $nil, $p_a ?? null, $p_b ?? null, $p_nil ?? null, $p_5 ?? null, $p_ ?? null
    ]), "\n";
}
foreach ([EXTR_OVERWRITE, EXTR_SKIP, EXTR_PREFIX_SAME, EXTR_PREFIX_ALL,
          EXTR_PREFIX_INVALID, EXTR_PREFIX_IF_EXISTS, EXTR_IF_EXISTS] as $f) { flags($f, 'p'); }
