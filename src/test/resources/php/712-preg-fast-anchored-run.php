<?php
// Fast path: a class or literal run anchored at the start of the subject.
function show($label, $value) {
    echo $label, ': ', var_export($value, true), "\n";
}

$cases = [
    ['/^(?:\/)+/u', '/a//b///', 'strip slashes'],
    ['/^(?:\/)+/u', '//', 'only slashes'],
    ['/^(?:\/)+/u', 'a/b', 'no leading slash'],
    ['/^(?:\/)+/u', 'é/x', 'multibyte first'],
    ['/^[a-z]+/', 'abc123', 'ascii run'],
    ['/^[a-z]+/', 'éabc', 'byte first'],
    ['/^[a-z]+/u', 'éabc', 'u byte first'],
    ['/^[a-z]+/u', 'abcé', 'u run then multibyte'],
    ['/^[^a]+/', 'bbbaaa', 'negated run'],
    ['/^[0-9]+/', '123x', 'digits'],
];

foreach ($cases as [$p, $s, $note]) {
    echo "== $note $p ", bin2hex($s), "\n";
    foreach ([0, PREG_OFFSET_CAPTURE, PREG_UNMATCHED_AS_NULL] as $flags) {
        $m = null;
        show('match-' . $flags, [preg_match($p, $s, $m, $flags), $m, preg_last_error()]);
    }
    $m = null;
    show('all', [preg_match_all($p, $s, $m, PREG_SET_ORDER | PREG_OFFSET_CAPTURE), $m]);
    show('split', preg_split($p, $s));
    show('replace', preg_replace($p, '<$0>', $s));
    show('replace-strip', preg_replace($p, '', $s));
}

// ^ is not multiline, so an offset cannot turn it into a match in the middle.
$m = null;
show('offset', [preg_match('/^[a-z]+/', 'abc', $m, 0, 1), $m]);
$m = null;
show('offset-u', [preg_match('/^(?:\/)+/u', '/abc', $m, 0, 1), $m]);

// Invalid UTF-8: rejected before the run is considered.
$m = null;
show('invalid', [preg_match('/^(?:\/)+/u', "\xFF", $m, PREG_OFFSET_CAPTURE), $m, preg_last_error(), preg_last_error_msg()]);
$m = null;
show('invalid-past', [preg_match('/^(?:\/)+/u', "\xFF", $m, 0, 2), $m, preg_last_error(), preg_last_error_msg()]);
