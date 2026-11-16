<?php
function demo($a, $b = 2, $c = 3) {
    return "$a:$b:$c";
}

try {
    demo(unknown: 1, a: 0);
} catch (Error $e) {
    echo "unk=" . get_class($e) . ": " . $e->getMessage() . "
";
}

try {
    demo(1, a: 2);
} catch (Error $e) {
    echo "ovw1=" . get_class($e) . ": " . $e->getMessage() . "
";
}

try {
    demo(...[10], a: 20);
} catch (Error $e) {
    echo "ovw2=" . get_class($e) . ": " . $e->getMessage() . "
";
}

try {
    demo(b: 5);
} catch (Error $e) {
    echo "miss=" . get_class($e) . ": " . $e->getMessage() . "
";
}

try {
    strlen(nope: 'abc');
} catch (Error $e) {
    echo "builtin=" . get_class($e) . ": " . $e->getMessage() . "
";
}
