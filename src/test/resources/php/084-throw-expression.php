<?php
function coalesceThrow($v) {
    return $v ?? throw new InvalidArgumentException("missing value");
}

function ternaryThrow($cond) {
    return $cond ? "yes" : throw new DomainException("cond false");
}

function shortCircuit($ok) {
    $ok && print("and-ok
");
    $ok || throw new RuntimeException("or-failed");
    return "done";
}

echo coalesceThrow("present"), "
";
try {
    coalesceThrow(null);
} catch (InvalidArgumentException $e) {
    echo "coalesce caught: ", $e->getMessage(), "
";
}

echo ternaryThrow(true), "
";
try {
    ternaryThrow(false);
} catch (DomainException $e) {
    echo "ternary caught: ", $e->getMessage(), "
";
}

echo shortCircuit(true), "
";
try {
    shortCircuit(false);
} catch (RuntimeException $e) {
    echo "or caught: ", $e->getMessage(), "
";
}

try {
    false or throw new LogicException("low-prec-or");
} catch (LogicException $e) {
    echo "low-or caught: ", $e->getMessage(), "
";
}

// Assignment inside throw expression
$captured = null;
try {
    throw $captured = new UnexpectedValueException("assigned");
} catch (UnexpectedValueException $e) {
    var_dump($captured === $e, $e->getMessage());
}
