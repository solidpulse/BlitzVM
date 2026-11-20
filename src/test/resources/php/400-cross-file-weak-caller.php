<?php
// A weak file calls functions declared in weak and strict files: the calling file decides
// parameter coercion; the declaring file decides return coercion.
require __DIR__ . '/inc/mode-weak.php';
require __DIR__ . '/inc/mode-strict.php';

function strip($s) {
    return str_replace(__DIR__ . '/', '', $s);
}

echo weakParam("5"), "\n";
echo strictParam("6"), "\n";
echo weakReturn(), "\n";
try { strictReturn(); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }

$weak = weakPropertyHolder();
$weak->number = "10";
var_dump($weak->number);
$strict = strictPropertyHolder();
$strict->number = "11";
var_dump($strict->number);
