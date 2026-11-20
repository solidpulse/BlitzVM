<?php
// The mode of the file doing a property write or calling a by-reference parameter decides.
function strip($s) {
    return str_replace(__DIR__ . '/', '', $s);
}

require __DIR__ . '/inc/assign-weak.php';
require __DIR__ . '/inc/assign-strict.php';

class Box {
    public int $number = 0;
}

$box = new Box();
assignWeak($box, '5');
var_dump($box->number);
try { assignStrict($box, '6'); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
var_dump($box->number);
try { assignStrict($box, 7); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
var_dump($box->number);

$value = '10';
var_dump(coerceWeakByRef($value), $value);
$value = '11';
try { var_dump(coerceStrictByRef($value)); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
var_dump($value);
