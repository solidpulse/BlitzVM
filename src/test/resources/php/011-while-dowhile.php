<?php
$i = 0;
while ($i < 5) {
    echo $i, " ";
    $i++;
}
echo "\n";

$j = 0;
do {
    echo $j, " ";
    $j++;
} while ($j < 5);
echo "\n";

$k = 10;
do {
    echo "once: ", $k, "\n";
} while ($k < 5);
