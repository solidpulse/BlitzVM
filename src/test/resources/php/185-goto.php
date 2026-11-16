<?php
// 1. Forward and backward goto at top-level
$out = [];
$i = 0;
loop_top:
if ($i < 3) {
    $out[] = $i;
    $i++;
    goto loop_top;
}
goto skip_section;
$out[] = 999;
skip_section:
$out[] = 'end';
echo implode(',', $out), "\n";

// 2. Jump out of loops and switch, and into an if block
function flowTest($flag) {
    $log = [];
    if ($flag) {
        $log[] = 'before-target';
        inside_if:
        $log[] = 'at-inside-if';
    } else {
        goto inside_if;
    }

    for ($a = 0; $a < 3; $a++) {
        switch ($a) {
            case 1:
                goto after_loops;
            default:
                $log[] = "a=$a";
        }
    }
    $log[] = 'unreachable';
    after_loops:
    $log[] = 'done';
    return implode('|', $log);
}
echo flowTest(false), "\n";

// 3. Jump out of try runs finally
function tryGoto() {
    $s = '';
    try {
        $s .= 'try-';
        goto after_try;
        $s .= 'skip-';
    } finally {
        $s .= 'fin-';
    }
    after_try:
    return $s . 'ok';
}
echo tryGoto(), "\n";

// 4. Loop with internal goto + error exit label (compat-utf8.php pattern)
function scanBytes($bytes) {
    $n = strlen($bytes);
    $res = [];
    $at = 0;
    while ($at < $n) {
        $b = ord($bytes[$at]);
        if ($b === 0) {
            goto invalid_byte;
        }
        $out = chr($b);
        if ($b >= 65 && $b <= 90) {
            $out = strtolower($out);
            goto flush_char;
        }
        $out = strtoupper($out);
        flush_char:
        $res[] = $out;
        $at++;
    }
    return implode('', $res);

    invalid_byte:
    return implode('', $res) . '!';
}
echo scanBytes("aBc"), '|', scanBytes("aB\0c"), "\n";

// 5. Goto inside a closure
$fn = function ($n) {
    $sum = 0;
    $k = 1;
    again:
    if ($k <= $n) {
        $sum += $k;
        $k++;
        goto again;
    }
    return $sum;
};
echo $fn(5), "\n";
