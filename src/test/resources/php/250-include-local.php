<?php
$x = 99;
function local_include($arg) {
    $x = 3;
    $value = require __DIR__ . '/inc/scope-basic.php';
    echo "$value:$x:$created\n";
}
local_include('one');
local_include('two');
echo "$x\n";
