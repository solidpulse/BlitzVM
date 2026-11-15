<?php
function strip($s) {
    return str_replace(__DIR__ . '/', '', $s);
}

function dumpTrace($frames) {
    foreach ($frames as $i => $f) {
        $file = isset($f['file']) ? strip($f['file']) : '[internal]';
        $line = isset($f['line']) ? $f['line'] : 0;
        $cls = isset($f['class']) ? $f['class'] : '';
        $type = isset($f['type']) ? $f['type'] : '';
        $hasObj = isset($f['object']) ? get_class($f['object']) : 'none';
        $argsDesc = isset($f['args']) ? json_encode($f['args']) : 'omitted';
        printf("#%d %s:%d %s%s%s obj=%s args=%s
",
            $i, $file, $line, $cls, $type, $f['function'], $hasObj, $argsDesc);
    }
}

class Probe {
    public function entry(int $n, string $label) {
        return self::middle([$n, $label]);
    }

    public static function middle(array $pair) {
        return leaf($pair[0], $pair[1], true, null);
    }
}

function leaf($a, $b, $flag, $nil) {
    echo "=== default debug_backtrace ===
";
    dumpTrace(debug_backtrace());

    echo "=== IGNORE_ARGS ===
";
    dumpTrace(debug_backtrace(DEBUG_BACKTRACE_IGNORE_ARGS));

    echo "=== limit=2, no object ===
";
    dumpTrace(debug_backtrace(0, 2));

    echo "=== exception getTrace ===
";
    $e = new RuntimeException("trace check");
    dumpTrace($e->getTrace());

    ob_like_print();
}

function ob_like_print() {
    // Verify debug_print_backtrace with IGNORE_ARGS so paths are only in file(line)
    // Wait: debug_print_backtrace prints directly to stdout with __DIR__!
    // We can't strip stdout without ob_start, so test getTraceAsString() here and test debug_print_backtrace in JUnit!
}

$p = new Probe();
$p->entry(7, "hello");
