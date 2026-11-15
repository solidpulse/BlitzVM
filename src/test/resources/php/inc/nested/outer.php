<?php
echo "outer:" . basename(__DIR__) . "\n";
$inner = require 'inner.php';
$again = require_once __DIR__ . '/inner.php';
return [$inner, $again];
