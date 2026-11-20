<?php
$preGlobal = 'entry';
define('ENTRY_FLAG', 'yes');
require_once __DIR__ . '/boot.php';

echo "boot saw global=", $sawPreGlobal, "\n";
echo "boot saw constant=", $sawFlag, "\n";
echo "shared=", $shared, "\n";
echo "copy=", $copy, "\n";
echo "counter=", next_counter(), " ", next_counter(), "\n";
echo "static=", Counter::$count, "\n";
Counter::$count++;
echo "static after increment=", Counter::$count, "\n";
echo "constant=", Config::VALUE, "\n";
echo "constant from boot=", $configValue, "\n";
echo "typed=", $typed->name, "\n";
echo "typed unset=", var_export(isset($typed->unset), true), "\n";
echo "static typed label=", StaticTyped::$label, " ", $staticLabel, "\n";
echo "global in function=", read_global(), "\n";
$shared = 'changed';
echo "reference=", $copy, "\n";

$typedRef = 'via reference';
echo "typed reference=", $typed->name, "\n";
try {
    $typedRef = [];
    echo "no type error\n";
} catch (TypeError $error) {
    echo "type error\n";
}
