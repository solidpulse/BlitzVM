<?php
function add_output(&$output, $text) { $output .= $text; }
function reference_reads() {
    $s = 'a'; $s .= 'b'; $copy = $s;
    add_output($s, 'c'); $alias =& $s; $alias .= 'd';
    echo $s, '|', $alias, '|', $copy, "\n";
    $capture = function () use (&$s) { $s .= 'e'; return $s; };
    echo $capture(), '|', $alias, "\n";
    $array = [$s, &$s]; $copyArray = $array;
    add_output($s, 'f'); echo implode('|', $array), '|', implode('|', $copyArray), "\n";
    $object = new stdClass(); $object->s =& $s;
    add_output($s, 'g'); echo json_encode($object), '|', serialize((array) $object), "\n";
    $clone = clone $object; $s .= 'h'; echo $object->s, '|', $clone->s, "\n";
    $s[0] = 'Z'; $s .= '!'; echo $s, "\n";
    $s = 'new'; $s .= 'value'; echo $alias, "\n";
    unset($s); $s = 'separate'; $s .= '!'; echo $s, '|', $alias, "\n";
}
reference_reads();
function &ref_return() { static $s = ''; $s .= 'x'; return $s; }
$r =& ref_return(); $r .= 'y'; $copy = ref_return(); $r .= 'z'; echo $r, '|', $copy, "\n";
