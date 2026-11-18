<?php
// A goto that stays inside a try runs its finally only once, when the try is left.
function f() {
    $i = 0;
    try {
        a:
        $i++;
        if ($i < 3) goto a;
    } finally {
        echo "fin\n";
    }
    echo $i, "\n";
}
f();
function g() {
    $i = 0;
    try {
        b:
        $i++;
        if ($i < 3) goto b;
        goto out;
    } finally {
        echo "fin2\n";
    }
    echo "skipped\n";
    out:
    echo "out $i\n";
}
g();
function h($n) {
    $r = [];
    loop:
    if ($n <= 0) goto done;
    foreach ([1, 2] as $x) {
        if ($x == 2) goto next;
        $r[] = "$n:$x";
    }
    next:
    $n--;
    goto loop;
    done:
    return implode(',', $r);
}
echo h(3), "\n";
function into($skip) {
    $log = [];
    if ($skip) goto deep;
    $log[] = 'start';
    try {
        $log[] = 'try';
        if ($skip > 1) {
            $log[] = 'never';
            deep:
            $log[] = 'deep';
        }
        try {
            $log[] = 'inner';
            last:
            $log[] = 'last';
        } finally {
            $log[] = 'inner-fin';
        }
    } finally {
        $log[] = 'fin';
    }
    if (count($log) < 12) goto last;
    return implode(',', $log);
}
echo into(0), "\n", into(1), "\n";
