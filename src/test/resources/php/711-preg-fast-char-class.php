<?php
// Fast path: one character class, with and without a + run.
function show($label, $value) {
    echo $label, ': ', var_export($value, true), "\n";
}

$cases = [
    ['/[&|]/', 'a=1&b=2|c=3'],
    ['/[&|]+/', 'a&&&b||c'],
    ['/[&|]+/', 'abc'],
    ['/[a-z]+/', '12abcXYdef'],
    ['/[^a]/', 'aaab'],
    ['/[^a]+/', 'aaabcc'],
    ['/[0-9]+/', 'a01b234c'],
    ['/[\d]+/', 'a01b234c'],
    ['/[\s]+/', "a \t\n b"],
    ['/[ \t]+/', "a \t b"],
    ['/[a-cx-z]+/', 'abqyz'],
    ['/[.]/', 'a.b'],
    ['/[\\]]/', 'a]b'],
    ['/[-a]+/', '-a-b'],
    ['/[a-]+/', '-a-b'],
    ['/[&|]/u', "aé&b😀|c"],
    ['/[a-z]+/u', "éabcé"],
];

foreach ($cases as [$p, $s]) {
    echo "== $p ", bin2hex($s), "\n";
    foreach ([0, PREG_OFFSET_CAPTURE, PREG_UNMATCHED_AS_NULL, PREG_OFFSET_CAPTURE | PREG_UNMATCHED_AS_NULL] as $flags) {
        $m = null;
        show('match-' . $flags, [preg_match($p, $s, $m, $flags), $m, preg_last_error()]);
    }
    $m = null;
    show('all-set', [preg_match_all($p, $s, $m, PREG_SET_ORDER | PREG_OFFSET_CAPTURE), $m]);
    $m = null;
    show('all-cols', [preg_match_all($p, $s, $m, PREG_OFFSET_CAPTURE), $m]);
    foreach ([0, PREG_SPLIT_NO_EMPTY, PREG_SPLIT_DELIM_CAPTURE, PREG_SPLIT_OFFSET_CAPTURE,
                 PREG_SPLIT_NO_EMPTY | PREG_SPLIT_DELIM_CAPTURE | PREG_SPLIT_OFFSET_CAPTURE] as $flags) {
        foreach ([-1, 1, 2, 3] as $limit) {
            show("split-$flags-$limit", [preg_split($p, $s, $limit, $flags), preg_last_error()]);
        }
    }
    show('replace', [preg_replace($p, '<$0>', $s), preg_last_error()]);
    show('replace-empty', preg_replace($p, '', $s));
    show('grep', preg_grep($p, [$s, 'abc', '']));
}

// Replace callbacks take the same fast match spans.
show('callback', preg_replace_callback('/[a-z]+/', fn($m) => strtoupper($m[0]), 'a1bc2d'));
$cbCount = 0;
show('callback-flags', [preg_replace_callback('/[a-z]+/', fn($m) => json_encode($m), 'ab cd', -1,
    $cbCount, PREG_OFFSET_CAPTURE), $cbCount]);
show('callback-array', preg_replace_callback_array([
    '/[a-z]+/' => fn($m) => 'x',
    '/[0-9]+/' => fn($m) => 'y',
], 'a1bc2d'));

// Offsets, including one past the end, and a negative offset.
$m = null;
show('offset-end', [preg_match('/[a-z]+/', 'abc', $m, 0, 3), $m]);
$m = null;
show('offset-past', [preg_match('/[a-z]+/', 'abc', $m, 0, 4), $m, preg_last_error(), preg_last_error_msg()]);
$m = null;
show('offset-negative', [preg_match('/[a-z]+/', 'abc', $m, 0, -2), $m]);
