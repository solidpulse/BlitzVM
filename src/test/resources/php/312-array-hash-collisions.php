<?php
$a = ['Aa' => 1, 'BB' => 2, 'AaAa' => 3, 'BBBB' => 4, 'AaBB' => 5, 'BBAa' => 6];
unset($a['AaAa'], $a['BB']); $a['BB'] = 7; $a['AaAa'] = 8;
var_dump($a);
$b = $a; $b['Aa'] = 9; unset($b['BBAa']); $b['new'] = 10; var_dump($a, $b);
$a = [];
for ($i = 0; $i < 1000; $i++) {
    $a['last'] = $i;
    unset($a['last']);
    $a['other'] = $i;
    unset($a['other']);
}
$a['last'] = 1000; var_dump($a);
