<?php
$pre = 'entry';
try {
    require_once __DIR__ . '/boot.php';
} catch (Throwable $error) {
    echo "CAUGHT\n";
} finally {
    echo "FINALLY\n";
}
echo "AFTER\n";
