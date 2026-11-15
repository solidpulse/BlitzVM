<?php
// 1. Closure and arrow function throwing and catching exceptions
$thrower = function (int $x) {
    if ($x < 0) {
        throw new InvalidArgumentException("neg:$x", 10);
    }
    return $x * 2;
};
$arrowThrow = fn($v) => $v ?? throw new DomainException("arrow-null");

try {
    echo $thrower(5), "
";
    $thrower(-3);
} catch (InvalidArgumentException $e) {
    echo "closure caught: ", $e->getMessage(), " code=", $e->getCode(), "
";
}

try {
    echo $arrowThrow("ok"), "
";
    $arrowThrow(null);
} catch (DomainException $e) {
    echo "arrow caught: ", $e->getMessage(), "
";
}

// 2. Exception unwinding through array_map + try/finally
$cleaned = false;
try {
    array_map(function ($n) use (&$cleaned) {
        try {
            if ($n === 2) {
                throw new RuntimeException("stop-at-2");
            }
            return $n * 10;
        } finally {
            if ($n === 2) {
                $cleaned = true;
            }
        }
    }, [1, 2, 3]);
} catch (RuntimeException $e) {
    echo "map caught: ", $e->getMessage(), " cleaned=", ($cleaned ? "yes" : "no"), "
";
}

// 3. ob_start callback transformation
ob_start(fn($buf) => strtoupper($buf));
echo "hello buffered world
";
ob_end_flush();

// 4. set_exception_handler + restore_exception_handler
set_exception_handler(function (Throwable $t) {
    echo "custom-eh: ", get_class($t), ": ", $t->getMessage(), "
";
});
$prev = set_exception_handler(function (Throwable $t) {
    echo "temp-eh
";
});
var_dump($prev instanceof Closure);
restore_exception_handler();
throw new LogicException("unhandled-to-handler");
