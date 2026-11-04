<?php
$t = true;
$f = false;

var_dump($t && $f);
var_dump($t || $f);
var_dump(!$t);
var_dump(!$f);
var_dump($t and $t);
var_dump($t and $f);
var_dump($t or $f);
var_dump($f or $f);

$val = true && false || true;
var_dump($val);
