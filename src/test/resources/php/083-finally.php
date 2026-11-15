<?php
// 1. Normal completion with try + finally (no catch) and try + catch + finally
$log = [];
try {
    $log[] = "try1";
} finally {
    $log[] = "finally1";
}
try {
    $log[] = "try2";
    throw new RuntimeException("boom");
} catch (RuntimeException $e) {
    $log[] = "catch2:" . $e->getMessage();
} finally {
    $log[] = "finally2";
}
echo implode(", ", $log), "
";

// 2. Uncaught exception passing through finally
$log = [];
try {
    try {
        $log[] = "inner-try";
        throw new LogicException("pass");
    } finally {
        $log[] = "inner-finally";
    }
} catch (LogicException $e) {
    $log[] = "outer-catch:" . $e->getMessage();
} finally {
    $log[] = "outer-finally";
}
echo implode(", ", $log), "
";

// 3. Return from try: return expression evaluated BEFORE finally runs
$trace = [];
function step($tag, $val) {
    global $trace;
    $trace[] = $tag;
    return $val;
}
function returnFromTry() {
    global $trace;
    $x = 10;
    try {
        return step("eval-return", $x);
    } finally {
        $x = 99;
        $trace[] = "in-finally";
    }
}
$r = returnFromTry();
echo "returnFromTry=", $r, " trace=", implode("->", $trace), "
";

// 4. Return from catch runs finally
function returnFromCatch() {
    global $trace;
    $trace = [];
    try {
        throw new RuntimeException("c");
    } catch (RuntimeException $e) {
        return step("catch-ret", "from-catch");
    } finally {
        $trace[] = "finally-after-catch-ret";
    }
}
echo "returnFromCatch=", returnFromCatch(), " trace=", implode("->", $trace), "
";

// 5. Return inside finally overrides return from try AND suppresses pending exception!
function finallyOverridesReturn() {
    try {
        return "from-try";
    } finally {
        return "from-finally";
    }
}
function finallySuppressesException() {
    try {
        throw new RuntimeException("discarded");
    } finally {
        return "suppressed-by-finally";
    }
}
echo finallyOverridesReturn(), " | ", finallySuppressesException(), "
";

// 6. break, continue, and break 2 / continue 2 through finally
$loopLog = [];
for ($i = 0; $i < 4; $i++) {
    try {
        if ($i === 1) {
            continue;
        }
        if ($i === 3) {
            break;
        }
        $loopLog[] = "body:$i";
    } finally {
        $loopLog[] = "fin:$i";
    }
}
echo "loop1: ", implode(" ", $loopLog), "
";

$nestedLog = [];
for ($outer = 0; $outer < 3; $outer++) {
    try {
        for ($inner = 0; $inner < 3; $inner++) {
            try {
                if ($outer === 0 && $inner === 1) {
                    continue 2;
                }
                if ($outer === 1 && $inner === 1) {
                    break 2;
                }
                $nestedLog[] = "$outer.$inner";
            } finally {
                $nestedLog[] = "if($outer.$inner)";
            }
        }
    } finally {
        $nestedLog[] = "of($outer)";
    }
}
echo "nested: ", implode(" ", $nestedLog), "
";

// 7. Exception thrown in finally replaces pending exception and links pending as previous
try {
    try {
        throw new InvalidArgumentException("original", 1);
    } finally {
        throw new RuntimeException("replacement", 2);
    }
} catch (Throwable $e) {
    echo "linked: ", get_class($e), ":", $e->getMessage(),
         " -> ", get_class($e->getPrevious()), ":", $e->getPrevious()->getMessage(), "
";
}

// 8. Chained linking when replacement already has a previous
try {
    try {
        throw new DomainException("first");
    } finally {
        $mid = new LogicException("middle");
        throw new OverflowException("top", 0, $mid);
    }
} catch (Throwable $e) {
    $names = [];
    for ($c = $e; $c !== null; $c = $c->getPrevious()) {
        $names[] = get_class($c) . ":" . $c->getMessage();
    }
    echo "chain3: ", implode(" -> ", $names), "
";
}
