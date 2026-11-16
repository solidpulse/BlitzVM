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
function reference_flags($flag) {
    $a = 10; $input = ['a' => 1, 'b' => 2];
    echo 'refs:', $flag, ':', extract($input, $flag | EXTR_REFS, 'p'), ':';
    $input['a'] = 7; $input['b'] = 8;
    echo json_encode([$a, $b ?? null, $p_a ?? null, $p_b ?? null]), "\n";
}
foreach ([0, 1, 2, 3, 4, 5, 6] as $f) { reference_flags($f); }
