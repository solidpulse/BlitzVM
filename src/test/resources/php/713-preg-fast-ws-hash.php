<?php
// Fast path: whitespace, a literal #, and the rest of the line, anchored by /A.
function show($label, $value) {
    echo $label, ': ', var_export($value, true), "\n";
}

$subjects = [
    'a: b # comment',
    'a: b',
    "a # one\nb # two",
    "# whole line",
    "  \t# indented",
    "a\n# later",
    '',
    "a#b",
    "a \t # x \r",
];
foreach ($subjects as $s) {
    echo "== ", bin2hex($s), "\n";
    foreach ([0, PREG_OFFSET_CAPTURE, PREG_UNMATCHED_AS_NULL] as $flags) {
        $m = null;
        show('match-' . $flags, [preg_match('/\s*#.*$/A', $s, $m, $flags), $m, preg_last_error()]);
    }
    show('replace', [preg_replace('/\s*#.*$/A', '', $s), preg_last_error()]);
    show('replace-count', [preg_replace('/\s*#.*$/A', '<$0>', $s, 1, $count), $count]);
    show('split', preg_split('/\s*#.*$/A', $s));
    show('grep', preg_grep('/\s*#.*$/A', [$s, 'x', '# y']));
}

// An offset inside the whitespace run: \G allows the match to start there.
$m = null;
show('offset-space', [preg_match('/\s*#.*$/A', 'a  # c', $m, PREG_OFFSET_CAPTURE, 1), $m]);
$m = null;
show('offset-hash', [preg_match('/\s*#.*$/A', 'a  # c', $m, PREG_OFFSET_CAPTURE, 3), $m]);
$m = null;
show('offset-bad', [preg_match('/\s*#.*$/A', 'a  # c', $m, 0, 2), $m]);
