<?php
function benchmark() {
    $sum = 0;
    // Bounded strings: 600,000 concatenations/strlen calls, not a giant quadratic string.
    for ($batch = 0; $batch < 6000; $batch = $batch + 1) {
        $s = '';
        for ($i = 0; $i < 100; $i = $i + 1) {
            $s = $s . 'wp:' . 'x';
            $sum = $sum + strlen($s);
        }
    }
    return $sum;
}
for ($round = 0; $round < 10; $round = $round + 1) {
    $start = hrtime(true);
    $result = benchmark();
    $elapsed = hrtime(true) - $start;
    echo 'round=', $round, ' ns=', $elapsed, ' result=', $result, "\n";
}
