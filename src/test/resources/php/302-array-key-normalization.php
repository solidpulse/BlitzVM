<?php
$a = ['5' => 'canonical', '05' => 'leading', '-0' => 'minus zero', '+5' => 'plus',
      '0' => 'zero', '-5' => 'negative', '9223372036854775808' => 'too large',
      '-9223372036854775808' => 'minimum', "\xB2" => 'non-ascii'];
$a[5] = 'updated'; $a[true] = 'boolean'; $a[null] = 'null';
var_dump($a, array_key_exists('5', $a), array_key_exists('05', $a));
$b = ['zero', 'one']; $b['1'] = 'updated'; $b['2'] = 'two'; var_dump($b, array_is_list($b));
