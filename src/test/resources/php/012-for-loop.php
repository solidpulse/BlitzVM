<?php
for ($i = 0; $i < 10; $i++) {
    if ($i == 2) {
        continue;
    }
    if ($i == 7) {
        break;
    }
    echo $i, " ";
}
echo "\n";

$sum = 0;
for ($x = 1; $x <= 5; $x++) {
    $sum += $x;
}
echo "Sum: ", $sum, "\n";
