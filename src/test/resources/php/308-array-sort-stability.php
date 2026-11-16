<?php
$a = [3, 1, 2, 1, 3, 2]; sort($a); var_dump($a);
$a = [['rank' => 2, 'id' => 'a'], ['rank' => 1, 'id' => 'b'],
      ['rank' => 2, 'id' => 'c'], ['rank' => 1, 'id' => 'd']];
usort($a, function($x, $y) { return $x['rank'] <=> $y['rank']; });
echo implode(',', array_column($a, 'id')), "\n";
$a = ['b' => 2, 'a' => 1, 'd' => 2, 'c' => 1]; asort($a); var_dump($a);
$a = [2, 1]; $b = $a; sort($b); var_dump($a, $b);
