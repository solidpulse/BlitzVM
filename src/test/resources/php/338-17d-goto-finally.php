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
