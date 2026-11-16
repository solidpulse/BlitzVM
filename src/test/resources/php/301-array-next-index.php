<?php
$a = ['a', 'b', 'c']; unset($a[2]); $a[] = 'd'; var_dump($a);
array_pop($a); $a[] = 'e'; var_dump($a);
$a = [0 => 'a', 5 => 'b']; unset($a[5]); array_pop($a); $a[] = 'c'; var_dump($a);
$a = [0 => 'a', 5 => 'b', 2 => 'c']; array_pop($a); $a[] = 'd'; var_dump($a);
$a = [0 => 'a', 5 => 'b', 's' => 'c']; unset($a[5]); array_pop($a); $a[] = 'd'; var_dump($a);
$a = [-5 => 'a']; $a[] = 'b'; array_pop($a); $a[] = 'c'; var_dump($a);
$a = [-1 => 'a']; $a[] = 'b'; var_dump($a);
$a = [-5 => 'a']; unset($a[-5]); $a[] = 'b'; var_dump($a);
$a = [-5 => 'a']; array_pop($a); $a[] = 'b'; var_dump($a);
$a = [9223372036854775806 => 'a']; $a[] = 'b';
try { $a[] = 'overflow'; } catch (Error $e) { echo $e->getMessage(), "\n"; }
array_pop($a); $a[] = 'again'; var_dump($a);
unset($a[9223372036854775807]); $a[] = 'reuse'; var_dump($a);
$a = [9223372036854775807 => 'a']; array_pop($a); $a[] = 'b'; var_dump($a);
