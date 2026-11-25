<?php
// Fast path: a pattern body that is a literal string.
function show($label, $value) {
    echo $label, ': ', var_export($value, true), "\n";
}

$cases = [
    ['/abc/', 'xxabcxx'],
    ['/abc/', 'ab'],
    ['/abc/', ''],
    ['/a\.b/', 'xa.bx a.b'],
    ['/\Qa.b\E/', 'xa.bx a.b'],
    ['/abc/i', 'ABc abc'],
    ['/0o755/', 'v0o755x'],
    ['/^abc/', "abc\nabc"],
    ['/^abc/', 'xabc'],
    ['/abc/A', 'abc'],
    ['/abc/A', 'xabc'],
];

foreach ($cases as [$p, $s]) {
    echo "== $p ", json_encode($s), "\n";
    $m = null;
    show('match', [preg_match($p, $s, $m), $m]);
    $m = null;
    show('offset', [preg_match($p, $s, $m, PREG_OFFSET_CAPTURE, 2), $m]);
    show('all', [preg_match_all($p, $s, $m, PREG_SET_ORDER | PREG_OFFSET_CAPTURE), $m]);
    show('split', preg_split($p, $s));
    show('split-flags', preg_split($p, $s, 2, PREG_SPLIT_NO_EMPTY | PREG_SPLIT_OFFSET_CAPTURE));
    show('replace', preg_replace($p, '<$0>', $s));
    show('replace-count', [preg_replace($p, '<$0>', $s, 1, $count), $count]);
    show('grep', preg_grep($p, [$s, 'abc', 'zzz']));
}
