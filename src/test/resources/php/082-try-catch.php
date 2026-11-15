<?php
class CustomA extends LogicException {}
class CustomB extends RuntimeException {}

// 1. Multiple catch ordering: more specific before general
function testOrder($which) {
    try {
        if ($which === 1) throw new CustomA("a");
        if ($which === 2) throw new DomainException("d");
        if ($which === 3) throw new LogicException("l");
        if ($which === 4) throw new CustomB("b");
        if ($which === 5) throw new TypeError("t");
        return "none";
    } catch (CustomA $e) {
        return "CustomA:" . $e->getMessage();
    } catch (LogicException $e) {
        return "Logic:" . $e->getMessage();
    } catch (CustomB | OverflowException $e) {
        return "Union:" . get_class($e) . ":" . $e->getMessage();
    } catch (Error $e) {
        return "Error:" . get_class($e) . ":" . $e->getMessage();
    } catch (Throwable $e) {
        return "Throwable:" . $e->getMessage();
    }
}

for ($i = 0; $i <= 5; $i++) {
    echo $i, " => ", testOrder($i), "
";
}

// 2. Variable-less catch (PHP 8) and unknown class in catch (never matches, no error)
try {
    throw new InvalidArgumentException("ignored");
} catch (NonExistentExceptionClassXYZ $e) {
    echo "should not match
";
} catch (NonExistentOne | InvalidArgumentException) {
    echo "matched union without variable
";
}

// 3. Catching via custom user interface implemented by user exception
interface Retryable {
    public function retryAfter(): int;
}
class RateLimitException extends RuntimeException implements Retryable {
    public function retryAfter(): int {
        return 30;
    }
}
try {
    throw new RateLimitException("slow down");
} catch (Retryable $r) {
    echo "retryable: ", $r->retryAfter(), " msg=", $r->getMessage(), "
";
}

// 4. Rethrow and nested try/catch
try {
    try {
        throw new RangeException("out of range", 12);
    } catch (RangeException $inner) {
        echo "inner caught: ", $inner->getCode(), "
";
        throw $inner;
    }
} catch (\Throwable $outer) {
    echo "outer caught same: ", ($outer === $inner ? "yes" : "no"), " ", get_class($outer), "
";
}

// 5. Throwing non-objects and non-Throwables raises catchable Error
foreach ([123, "str", null, [1], new stdClass()] as $bad) {
    try {
        throw $bad;
    } catch (Error $e) {
        echo "bad throw => ", get_class($e), ": ", $e->getMessage(), "
";
    }
}
