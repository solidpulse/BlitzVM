<?php

$seeds = [0, 1, 7, 42, -1, PHP_INT_MAX, PHP_INT_MIN, 123456789];
foreach ($seeds as $seed) {
    foreach ([MT_RAND_MT19937, MT_RAND_PHP] as $mode) {
        echo "seed $seed mode $mode\n";

        mt_srand($seed, $mode);
        echo " mt_rand";
        for ($i = 0; $i < 12; $i++) {
            echo " ", mt_rand();
        }
        echo "\n";

        mt_srand($seed, $mode);
        echo " rand";
        for ($i = 0; $i < 6; $i++) {
            echo " ", rand();
        }
        echo "\n";

        foreach ([[0, 1], [0, 2], [0, 3], [0, 10], [5, 9], [-5, 5], [0, 1000], [5, 1], [0, PHP_INT_MAX], [PHP_INT_MIN, PHP_INT_MAX]] as [$min, $max]) {
            mt_srand($seed, $mode);
            echo " range $min $max";
            for ($i = 0; $i < 5; $i++) {
                echo " ", rand($min, $max);
            }
            echo "\n";
        }

        mt_srand($seed, $mode);
        $array = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
        shuffle($array);
        echo " shuffle ", implode(',', $array), "\n";

        mt_srand($seed, $mode);
        echo " str_shuffle ", bin2hex(str_shuffle(implode('', array_map('chr', range(0, 25))))), "\n";

        foreach ([1, 2, 4, 6] as $count) {
            mt_srand($seed, $mode);
            echo " array_rand $count ", json_encode(array_rand(['a', 'b', 'c', 'd', 'e', 'f'], $count)), "\n";
        }

        mt_srand($seed, $mode);
        echo " array_rand assoc ", json_encode(array_rand(['x' => 1, 'y' => 2, 'z' => 3, 'w' => 4], 2)), "\n";
    }
}

mt_srand(42);
echo "srand alias ";
srand(42);
echo mt_rand(), " ", rand(), "\n";

mt_srand(42, 5);
echo "invalid mode ignored ", mt_rand(), "\n";

echo "max ", mt_getrandmax(), " ", getrandmax(), "\n";

srand(1);
$value = lcg_value();
var_dump(is_float($value), $value >= 0.0, $value < 1.0);

try {
    mt_srand("abc");
} catch (TypeError $e) {
    echo $e->getMessage(), "\n";
}
try {
    srand([]);
} catch (TypeError $e) {
    echo $e->getMessage(), "\n";
}
try {
    mt_rand(5);
} catch (ArgumentCountError $e) {
    echo $e->getMessage(), "\n";
}
try {
    rand(5);
} catch (ArgumentCountError $e) {
    echo $e->getMessage(), "\n";
}
try {
    mt_rand(5, 1);
} catch (ValueError $e) {
    echo $e->getMessage(), "\n";
}
try {
    array_rand([]);
} catch (ValueError $e) {
    echo $e->getMessage(), "\n";
}
try {
    array_rand([1, 2], 3);
} catch (ValueError $e) {
    echo $e->getMessage(), "\n";
}
try {
    random_int(5, 1);
} catch (ValueError $e) {
    echo $e->getMessage(), "\n";
}
try {
    random_bytes(0);
} catch (ValueError $e) {
    echo $e->getMessage(), "\n";
}
