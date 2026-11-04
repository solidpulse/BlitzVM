<?php
// A CPU hog: without preemption this would occupy an execution slot until it finishes.
$seconds = (float)($_GET['s'] ?? 3);
$end = microtime(true) + $seconds;
$i = 0;
while (microtime(true) < $end) {
    $i++;
}
echo "spun $i iterations in {$seconds}s\n";
