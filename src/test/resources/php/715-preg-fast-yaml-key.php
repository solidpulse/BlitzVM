<?php
// Fast path: the key/value line pattern symfony/yaml uses on every front matter line.
function show($label, $value) {
    echo $label, ': ', var_export($value, true), "\n";
}

$pattern = '#^(?P<key>(?:![^\s]++\s++)?(?:(?:"([^"\\]*+(?:\\.[^"\\]*+)*+)"|\'([^\']*+(?:\'\'[^\']*+)*+)\')|[^ \'"\[\{!].*?)) *\:(( |\t)++(?P<value>.+))?$#u';

$lines = [
    'title: Tempest alpha 2',
    'description: a:b c',
    'description: Tempest alpha 2 is released with auth support, caching, and more!',
    '"quoted key": v',
    "'single quoted': v",
    "'it''s here': v",
    '!tag key: value',
    '!tag "quoted": value',
    'a:b: c',
    'a:b:c',
    'a: b: c',
    'a:',
    'a: ',
    'a:   ',
    'a:  x  ',
    'a:b',
    'key: a # comment',
    'key with spaces : value',
    ': value',
    '[]: value',
    '{x}: value',
    '!nospace: value',
    'a-b_c.d: value',
    '',
    '   ',
    "a: b\nsecond: c",
    "\xFF: value",
];
foreach ($lines as $s) {
    echo "== ", bin2hex($s), "\n";
    foreach ([0, PREG_OFFSET_CAPTURE, PREG_UNMATCHED_AS_NULL,
                 PREG_OFFSET_CAPTURE | PREG_UNMATCHED_AS_NULL] as $flags) {
        $m = null;
        show('match-' . $flags, [preg_match($pattern, $s, $m, $flags), $m, preg_last_error(), preg_last_error_msg()]);
    }
    $m = null;
    show('all', [preg_match_all($pattern, $s, $m, PREG_SET_ORDER | PREG_OFFSET_CAPTURE), $m]);
    show('split', preg_split($pattern, $s));
    show('replace', preg_replace($pattern, '<$key=$value>', $s));
    show('grep', preg_grep($pattern, [$s, 'x: y']));
}

// Replace callbacks see the same named groups and spans.
show('callback', preg_replace_callback($pattern, fn($m) => '[' . $m['key'] . '=' . ($m['value'] ?? '') . ']', 'title: x'));
$cbCount = 0;
show('callback-flags', [preg_replace_callback($pattern, fn($m) => json_encode($m), 'a: b', -1,
    $cbCount, PREG_OFFSET_CAPTURE), $cbCount]);

// The pattern's ^ is not multiline, so match_all only sees a line at offset 0.
$m = null;
show('multiline-all', [preg_match_all($pattern, "a: b\nc: d", $m), $m]);
$m = null;
show('offset', [preg_match($pattern, 'title: x', $m, PREG_OFFSET_CAPTURE, 1), $m]);
