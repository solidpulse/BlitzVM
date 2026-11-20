<?php
require_once __DIR__ . '/boot.php';

echo "pure=", implode(',', $pure), "\n";
echo "nested=", implode(',', $pureNested['inner']), "\n";
echo "objects=", $objectArray[0]->name, ",", $objectArray[1]->name, "\n";
echo "ref=", $refArray['x'], "\n";

$pure[] = 'entry';
$pureNested['inner'][] = 'entry';
$objectArray[0]->name = 'changed';
$objectArray[] = new stdClass();
$objectArray[2]->name = 'third';
$refArray['x'] = 'entry changed';

echo "after pure=", implode(',', $pure), "\n";
echo "after nested=", implode(',', $pureNested['inner']), "\n";
echo "after objects=", count($objectArray), ":", $objectArray[0]->name, ",", $objectArray[2]->name, "\n";
echo "after ref=", $shared, "\n";
