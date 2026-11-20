<?php
require_once __DIR__ . '/common.php';
$entryGlobal = 'from entry';
define('ENTRY_CONSTANT', 'entry constant');
require_once __DIR__ . '/boot.php';

echo "boot saw global=", $sawGlobal, "\n";
echo "boot saw constant=", $sawConstant, "\n";
echo "boot saw include=", $sawCommon, "\n";
echo "entry global=", $entryGlobal, "\n";
echo "entry constant=", ENTRY_CONSTANT, "\n";
echo "after boot=", $afterBoot, "\n";
echo "pre-boot include=", common_value(), "\n";
