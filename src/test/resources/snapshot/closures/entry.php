<?php
require_once __DIR__ . '/boot.php';

echo "bound=", $bound('world'), "\n";
echo "bound id=", spl_object_id($boundThis), "\n";
echo "static=", ($staticClosure)(), "\n";
echo "count1=", ($byRef)(), "\n";
echo "count2=", ($byRef)(), "\n";
echo "count global=", $count, "\n";
echo "first class=", ($firstClass)('x'), "\n";
echo "held=", ($holder->callback)('held'), "\n";
echo "closure static=", ($closureStatic)(), " ", ($closureStatic)(), "\n";
