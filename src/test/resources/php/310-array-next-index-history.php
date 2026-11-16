<?php
$a = ['s' => 1, -5 => 2]; $a[] = 3; var_dump($a);
$a = [-9223372036854775808 => 1]; $a[] = 2; var_dump($a);
$a = [1]; array_pop($a); $a[-5] = 2; $a[] = 3; var_dump($a);
$a = [1]; unset($a[0]); $a[-5] = 2; $a[] = 3; var_dump($a);
$a = [-5 => 1]; array_pop($a); $a[-10] = 2; $a[] = 3; var_dump($a);
$a = [-5 => 1]; unset($a[-5]); $a[-10] = 2; $a[] = 3; var_dump($a);
$a = [-5 => 1, 's' => 2]; array_pop($a); $a[] = 3; var_dump($a);
$a = [0 => 1, 5 => 2]; unset($a[5]); $a[] = 3; array_pop($a); $a[] = 4; var_dump($a);
$a = [1, 2]; unset($a[1]); $a[1] = 3; $a[] = 4; var_dump($a);
