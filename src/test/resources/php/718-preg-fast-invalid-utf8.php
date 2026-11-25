<?php
// Invalid UTF-8 on the /u fast paths, and the calls that do not look at the subject.
function show($label, $value) {
    echo $label, ': ', var_export($value, true), "\n";
}

$patterns = [
    '/[a-z]+/u',
    '/^(?:\/)+/u',
    '/^(?:\+|-)?0o(?P<value>[0-7_]++)$/u',
    '#^(?P<key>(?:![^\s]++\s++)?(?:(?:"([^"\\]*+(?:\\.[^"\\]*+)*+)"|\'([^\']*+(?:\'\'[^\']*+)*+)\')|[^ \'"\[\{!].*?)) *\:(( |\t)++(?P<value>.+))?$#u',
];
$subjects = ["\xFF", "a\xFFb", "\xC3", "\xC3\xA9", "\xED\xA0\x80", "title: \xFF"];
foreach ($patterns as $p) {
    echo "== $p\n";
    foreach ($subjects as $s) {
        echo "-- ", bin2hex($s), "\n";
        $m = null;
        show('match', [preg_match($p, $s, $m), $m, preg_last_error(), preg_last_error_msg()]);
        $m = null;
        show('all', [preg_match_all($p, $s, $m), $m, preg_last_error()]);
        $m = null;
        show('all-set', [preg_match_all($p, $s, $m, PREG_SET_ORDER), $m, preg_last_error()]);
        // A limit that prevents any split does not validate the subject; any other does.
        show('split-1', [preg_split($p, $s, 1, PREG_SPLIT_OFFSET_CAPTURE), preg_last_error()]);
        show('split-2', [preg_split($p, $s, 2), preg_last_error()]);
        show('split', [preg_split($p, $s), preg_last_error()]);
        // A zero replacement limit also leaves the subject alone.
        show('replace-0', [preg_replace($p, 'x', $s, 0), preg_last_error()]);
        show('replace', [preg_replace($p, 'x', $s), preg_last_error()]);
        show('callback-0', [preg_replace_callback($p, fn($m) => 'x', $s, 0), preg_last_error()]);
    }
}

// An offset past the end is checked before the UTF-8 subject.
$m = null;
show('offset-past', [preg_match('/[a-z]+/u', "\xFF", $m, 0, 2), $m, preg_last_error(), preg_last_error_msg()]);
