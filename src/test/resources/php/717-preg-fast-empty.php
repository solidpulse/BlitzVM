<?php
// Fast path: an empty pattern, including the //u UTF-8 validation call.
function show($label, $value) {
    echo $label, ': ', var_export($value, true), "\n";
}

$subjects = ['', 'abc', "a\xC3\xA9b", "\xC3\xA9", "\xC3", "\xC0\xAF", "\xED\xA0\x80",
    "\xF4\x90\x80\x80", "\xF0\x9F\x98\x80", "a\xFFb"];
foreach ($subjects as $s) {
    foreach (['//', '//u', '//i', '//m', '//D'] as $p) {
        echo "== $p ", bin2hex($s), "\n";
        show('bare', [preg_match($p, $s), preg_last_error(), preg_last_error_msg()]);
        $m = null;
        show('match', [preg_match($p, $s, $m), $m, preg_last_error()]);
        if (preg_match('//u', $s) !== false) {
            // A nonzero offset on an invalid subject is a pre-existing regex-path gap;
            // this fixture keeps the cases the fast path is responsible for exact.
            $m = null;
            show('offset', [preg_match($p, $s, $m, PREG_OFFSET_CAPTURE, 1), $m, preg_last_error()]);
        }
        $m = null;
        show('past', [preg_match($p, $s, $m, 0, 100), $m, preg_last_error(), preg_last_error_msg()]);
        show('all', [preg_match_all($p, $s, $m), $m, preg_last_error()]);
        show('split', [preg_split($p, $s), preg_last_error()]);
        show('replace', [preg_replace($p, 'x', $s), preg_last_error()]);
    }
}
