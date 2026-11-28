<?php

function fbits(float $value): string
{
    return bin2hex(pack('E', $value));
}

$engines = [
    fn() => new Random\Engine\Mt19937(42),
    fn() => new Random\Engine\Mt19937(7, MT_RAND_PHP),
    fn() => new Random\Engine\PcgOneseq128XslRr64(42),
    fn() => new Random\Engine\PcgOneseq128XslRr64("0123456789abcdef"),
    fn() => new Random\Engine\Xoshiro256StarStar(42),
];

foreach ($engines as $index => $make) {
    echo "engine $index\n";

    $randomizer = new Random\Randomizer($make());
    echo "nextInt";
    for ($i = 0; $i < 8; $i++) {
        echo " ", $randomizer->nextInt();
    }
    echo "\n";

    $randomizer = new Random\Randomizer($make());
    echo "nextFloat";
    for ($i = 0; $i < 8; $i++) {
        echo " ", fbits($randomizer->nextFloat());
    }
    echo "\n";

    $ranges = [
        [0, 0], [0, 1], [0, 2], [0, 3], [0, 10], [5, 9], [-5, 5], [-10, -6],
        [0, 1000], [0, 4294967295], [0, 4294967296], [0, 4294967297],
        [0, PHP_INT_MAX], [PHP_INT_MIN, 0], [PHP_INT_MIN, PHP_INT_MAX],
        [100, 100], [123, 456789012345],
    ];
    foreach ($ranges as [$min, $max]) {
        $randomizer = new Random\Randomizer($make());
        echo "getInt $min $max";
        for ($i = 0; $i < 4; $i++) {
            echo " ", $randomizer->getInt($min, $max);
        }
        echo "\n";
    }

    $intervals = [
        [0.0, 1.0], [1.0, 2.0], [-1.0, 1.0], [-2.0, -1.0], [0.1, 0.2],
        [1e-10, 1e-9], [-1e300, 1e300], [1.0, 1.0000000000000002],
        [-0.0, 5e-324], [100.0, 100.5],
    ];
    foreach (['ClosedOpen', 'ClosedClosed', 'OpenClosed', 'OpenOpen'] as $boundary) {
        foreach ($intervals as [$min, $max]) {
            $randomizer = new Random\Randomizer($make());
            echo "getFloat $boundary ", fbits($min), " ", fbits($max);
            for ($i = 0; $i < 3; $i++) {
                try {
                    $value = $randomizer->getFloat($min, $max, constant("Random\\IntervalBoundary::$boundary"));
                    echo " ", fbits($value);
                } catch (ValueError $e) {
                    echo " ", $e->getMessage();
                }
            }
            echo "\n";
        }
    }
}

$randomizer = new Random\Randomizer(new Random\Engine\Mt19937(42));
try {
    $randomizer->getInt(5, 1);
} catch (ValueError $e) {
    echo $e->getMessage(), "\n";
}
try {
    $randomizer->getFloat(1.0, 1.0);
} catch (ValueError $e) {
    echo $e->getMessage(), "\n";
}
try {
    $randomizer->getFloat(1.0, 1.0, Random\IntervalBoundary::ClosedClosed);
} catch (ValueError $e) {
    echo $e->getMessage(), "\n";
} catch (TypeError $e) {
    echo $e->getMessage(), "\n";
}
echo "equal closed ", fbits($randomizer->getFloat(1.0, 1.0, Random\IntervalBoundary::ClosedClosed)), "\n";
foreach ([INF, -INF, NAN] as $bad) {
    try {
        $randomizer->getFloat($bad, 1.0);
    } catch (ValueError $e) {
        echo $e->getMessage(), "\n";
    }
    try {
        $randomizer->getFloat(0.0, $bad);
    } catch (ValueError $e) {
        echo $e->getMessage(), "\n";
    }
}
try {
    $randomizer->getFloat(1.0, 1.0000000000000002, Random\IntervalBoundary::OpenOpen);
} catch (ValueError $e) {
    echo $e->getMessage(), "\n";
}
try {
    $randomizer->getFloat(0.0, 1.0, 5);
} catch (TypeError $e) {
    echo $e->getMessage(), "\n";
}
try {
    $randomizer->getInt(1);
} catch (ArgumentCountError $e) {
    echo $e->getMessage(), "\n";
}
try {
    $randomizer->getFloat(1.0);
} catch (ArgumentCountError $e) {
    echo $e->getMessage(), "\n";
}

// Intervals whose number of representable values does not fit in a double.
$tinyNegative = unpack('E', hex2bin('9627441ed421efd2'))[1];
$tinyPositive = unpack('E', hex2bin('161b593a781f4d95'))[1];
$maxDouble = unpack('E', hex2bin('7fefffffffffffff'))[1];
foreach ($engines as $make) {
    foreach ([
        [$tinyNegative, $tinyPositive],
        [-$maxDouble, $maxDouble],
    ] as [$wideMin, $wideMax]) {
        foreach (['ClosedOpen', 'ClosedClosed', 'OpenClosed', 'OpenOpen'] as $boundary) {
            $randomizer = new Random\Randomizer($make());
            echo "wide $boundary ", fbits($wideMin), " ", fbits($wideMax);
            for ($i = 0; $i < 3; $i++) {
                try {
                    $value = $randomizer->getFloat($wideMin, $wideMax, constant("Random\\IntervalBoundary::$boundary"));
                    echo " ", fbits($value);
                } catch (ValueError $e) {
                    echo " ", $e->getMessage();
                }
            }
            echo "\n";
        }
    }
}
