<?php
define('APP_ENTRY', 'entry');

require_once __DIR__ . '/app-boot.php';

header('X-App-Request-Time: ' . APP_REQUEST_TIME);
header('X-App-After: ' . (isset($GLOBALS['after_restore_ran']) ? 'yes' : 'no'));

echo 'boot=' . APP_BOOT . "\n";
echo 'global=' . $app_counter . "\n";
echo 'object=' . $app_service->name . "\n";
echo 'static=' . AppState::$count . "\n";
echo 'describe=' . AppState::describe() . "\n";

// Restored requests must see the snapshot values again, not what a previous request left.
AppState::$count = 42;
$app_counter = 99;
$app_service->name = 'changed';
