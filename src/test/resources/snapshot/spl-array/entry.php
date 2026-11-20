<?php
require_once __DIR__ . '/boot.php';

echo "value=", $arrayObject['a'], "\n";
echo "object=", $arrayObject['object']->name, "\n";
$arrayObject['b'] = 2;
echo "count=", count($arrayObject), "\n";
foreach ($arrayIterator as $value) {
    echo "iter=", $value, "\n";
}
$arrayIterator[] = 30;
echo "appended=", implode(',', iterator_to_array($arrayIterator)), "\n";
