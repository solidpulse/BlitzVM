<?php
declare(strict_types=1);

// A strict file calls functions and writes properties declared in weak and strict files.
require __DIR__ . '/inc/mode-weak.php';
require __DIR__ . '/inc/mode-strict.php';

function strip($s) {
    return str_replace(__DIR__ . '/', '', $s);
}

function takesInt(int $x) { echo 'takesInt ', var_export($x, true), "\n"; }

takesInt(1);
try { takesInt("1"); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
try { weakParam("7"); } catch (TypeError $e) { echo 'weakParam ', strip($e->getMessage()), "\n"; }
try { strictParam("8"); } catch (TypeError $e) { echo 'strictParam ', strip($e->getMessage()), "\n"; }
echo weakParam(9), "\n";
echo strictParam(10), "\n";
echo weakReturn(), "\n";
try { strictReturn(); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }

$weak = weakPropertyHolder();
try { $weak->number = "12"; } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
$weak->number = 13;
var_dump($weak->number);
$strict = strictPropertyHolder();
try { $strict->number = "14"; } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
$strict->number = 15;
var_dump($strict->number);
