<?php
declare(strict_types=1);
foreach ([fn() => unionArg(new Square()), fn() => unionArg(42), fn() => strs('a', 5), fn() => ints(1, '2'), fn() => floats(1, 2)] as $i => $case) {
    try {
        echo 's', $i, ': ', var_export($case(), true), "\n";
    } catch (TypeError $e) {
        echo 's', $i, ': TypeError: ', preg_replace('/, called in .*/', '', $e->getMessage()), "\n";
    }
}
