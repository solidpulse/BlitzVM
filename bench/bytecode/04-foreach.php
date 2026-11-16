<?php
function benchmark() {
    $a = [];
    for ($i = 0; $i < 1000; $i = $i + 1) {
        $a[] = $i;
    }
    $sum = 0;
    for ($repeat = 0; $repeat < 1000; $repeat = $repeat + 1) {
        foreach ($a as $k => $v) {
            $sum = $sum + $v;
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
