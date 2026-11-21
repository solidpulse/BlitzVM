<?php
spl_autoload_register(function (string $name): void {
    echo 'AUTOLOAD ', $name, "\n";
});

var_dump(class_exists('Missing.Class'));
var_dump(interface_exists('Missing-Class'));
var_dump(trait_exists('1Missing'));
var_dump(class_exists('Missing\\Valid'));
var_dump(class_exists('Missing Valid'));
