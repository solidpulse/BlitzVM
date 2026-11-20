<?php
$before = ['entry' => true];
require_once __DIR__ . '/boot.php';

echo "name=", $app->name, "\n";
echo "app id=", spl_object_id($app), "\n";
echo "first id=", spl_object_id($app->first), "\n";
echo "shared=", $app->shared === $app->first ? "yes" : "no", "\n";
echo "cycle=", $app->first->next->next === $app->first ? "yes" : "no", "\n";
echo "alias=", $app->first->alias === $app->second ? "yes" : "no", "\n";

$fresh = new Item('fresh');
echo "fresh id=", spl_object_id($fresh), "\n";
$app->first->name = 'mutated';
echo "mutated=", $app->first->name, "\n";
