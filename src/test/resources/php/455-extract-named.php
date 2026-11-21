<?php
$data = ['a' => 1, 'b' => 2];
$existing = 'kept';

var_dump(extract($data, flags: EXTR_SKIP));
var_dump($a, $b);

$data = ['b' => 20, 'c' => 30];
var_dump(extract($data, flags: EXTR_PREFIX_ALL, prefix: 'p'));
var_dump($p_b, $p_c);

$data = ['value' => 5];
var_dump(extract($data, prefix: 'unused'));
var_dump($value);
