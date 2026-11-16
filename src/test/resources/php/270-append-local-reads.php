<?php
function copied($s) { $s .= 'arg'; return $s; }
function make_string() { $s = ''; $s .= 'returned'; return $s; }
function reads() {
    $s = 'a'; $s .= 'b'; $copy = $s;
    $s .= 'c'; $copy .= 'd';
    echo "$s|$copy|", copied($s), '|', $s, "\n";
    $capture = function () use ($s) { $s .= 'cap'; return $s; };
    $arrow = fn() => $s;
    $array = [$s]; $object = new stdClass(); $object->s = $s;
    $s .= 'e';
    echo $capture(), '|', $arrow(), '|', $array[0], '|', $object->s, '|', $s, "\n";
    echo strlen($s), '|', $s[1], '|', $s[-1], '|', bin2hex($s), "\n";
    var_dump($s === 'abce', $s < 'z', isset($s), empty($s), $s ?? 'missing');
    $s[1] = 'Z'; $s .= '!'; echo $s, "\n";
    echo ($s .= '?'), '|', $s, '|', make_string(), "\n";
    $zero = ''; $zero .= '0'; var_dump(empty($zero), (bool) $zero);
    $number = '1'; $number .= '2'; $number += 3; var_dump($number);
    $bits = 'a'; $bits .= 'b'; $bits ^= 'AB'; echo bin2hex($bits), "\n";
    unset($s); $s = 'new'; $s .= 'value'; echo $s, "\n";
}
reads();
