<?php
// Exception traces and debug_backtrace through every kind of guest call.
function frame($f) {
    $fn = $f['function'] ?? '?';
    if (str_starts_with($fn, '{closure:')) {
        $fn = '{closure}';
    }
    return ($f['class'] ?? '') . ($f['type'] ?? '') . $fn . ':' . ($f['line'] ?? 0);
}

function collect($e) {
    return implode(' | ', array_map('frame', $e->getTrace()));
}

function thrower($message) {
    throw new RuntimeException($message);
}

function outer() {
    thrower('from function');
}
try {
    outer();
} catch (RuntimeException $e) {
    echo collect($e), "\n";
}

class Worker {
    public function method($message) {
        throw new RuntimeException($message);
    }
    public static function staticMethod($message) {
        throw new RuntimeException($message);
    }
    public function viaMagic($name, $args) {
        throw new RuntimeException('from __call');
    }
    public function __call($name, $args) {
        throw new RuntimeException('from __call');
    }
}
$w = new Worker();
try {
    $w->method('from method');
} catch (RuntimeException $e) {
    echo collect($e), "\n";
}
try {
    Worker::staticMethod('from static');
} catch (RuntimeException $e) {
    echo collect($e), "\n";
}
try {
    $w->missing('from __call');
} catch (RuntimeException $e) {
    echo collect($e), "\n";
}

$closure = function ($message) {
    throw new RuntimeException($message);
};
try {
    $closure('from closure');
} catch (RuntimeException $e) {
    echo collect($e), "\n";
}
$fcc = thrower(...);
try {
    $fcc('from first-class');
} catch (RuntimeException $e) {
    echo collect($e), "\n";
}
try {
    call_user_func($closure, 'from call_user_func');
} catch (RuntimeException $e) {
    echo collect($e), "\n";
}
try {
    array_map(fn($v) => thrower($v), ['from array_map']);
} catch (RuntimeException $e) {
    echo collect($e), "\n";
}

class MagicBag {
    public function __get($name) {
        throw new RuntimeException("from __get:$name");
    }
}
$magic = new MagicBag();
try {
    $magic->missing;
} catch (RuntimeException $e) {
    echo collect($e), "\n";
}

function deep($n) {
    if ($n === 0) {
        throw new LogicException('bottom');
    }
    deep($n - 1);
}
try {
    deep(3);
} catch (LogicException $e) {
    echo collect($e), "\n";
}
echo count($e->getTrace()), "\n";

function bt() {
    return array_map('frame', debug_backtrace(DEBUG_BACKTRACE_IGNORE_ARGS));
}
function btOuter() {
    return bt();
}
echo implode(' | ', btOuter()), "\n";

$limit = debug_backtrace(DEBUG_BACKTRACE_IGNORE_ARGS, 2);
echo count($limit), "\n";
echo basename($limit[0]['file'] ?? ''), ':', $limit[0]['line'] ?? 0, "\n";
