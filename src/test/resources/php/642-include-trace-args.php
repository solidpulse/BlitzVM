<?php
function names(array $t) { return array_map(fn($f) => basename($f['file'] ?? '-') . ':' . ($f['line'] ?? '-') . ' ' . $f['function'] . (isset($f['args']) ? '(' . implode(',', array_map('basename', $f['args'])) . ')' : ''), $t); }
try { include __DIR__ . '/inc/642-a.php'; } catch (Exception $e) { echo json_encode(names($e->getTrace())), "\n"; }
$e = include __DIR__ . '/inc/642-b.php'; echo json_encode(names($e->getTrace())), "\n";
$e = include __DIR__ . '/inc/642-c.php'; echo json_encode(names($e->getTrace())), "\n";
function f642() { return include __DIR__ . '/inc/642-c.php'; }
$e = f642(); echo json_encode(names($e->getTrace())), "\n";
