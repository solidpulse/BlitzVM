<?php
function change(&$s) { $s = 'changed'; return '!'; }
function evaluation() {
    $s = 'a'; $s .= ($s .= 'b'); echo $s, "\n";
    $s = 'a'; echo ($s .= change($s)), '|', $s, "\n";
    $s = 'a'; $s .= $s; echo $s, "\n";
    $a = ['x', 'y']; $i = 0; $a[$i++] .= 'z'; echo implode('|', $a), '|', $i, "\n";
    $s = ''; for ($i = 0; $i < 1000; $i++) { $s .= 'ab'; }
    $copy = $s; $s .= '!'; echo strlen($copy), '|', strlen($s), "\n";
}
evaluation();
