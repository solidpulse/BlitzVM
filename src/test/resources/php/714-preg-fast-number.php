<?php
// Fast path: ^, an optional sign, a literal and a class run ending at $.
function show($label, $value) {
    echo $label, ': ', var_export($value, true), "\n";
}

$patterns = [
    '/^(?:\+|-)?0o(?P<value>[0-7_]++)$/',
    '/^[+-]?0o[0-7_]++$/',
    '/^[0-9]+[_0-9]*$/',
];
$subjects = ['0o755', '-0o7_5', '+0o0', '0o', '0o8', '_0o7', '0O7', "0o7\n", '', '123', '1_2_3', '12a'];

foreach ($patterns as $p) {
    echo "== $p\n";
    foreach ($subjects as $s) {
        echo "-- ", bin2hex($s), "\n";
        foreach ([0, PREG_OFFSET_CAPTURE, PREG_UNMATCHED_AS_NULL,
                     PREG_OFFSET_CAPTURE | PREG_UNMATCHED_AS_NULL] as $flags) {
            $m = null;
            show('match-' . $flags, [preg_match($p, $s, $m, $flags), $m, preg_last_error()]);
        }
        $m = null;
        show('all-set', [preg_match_all($p, $s, $m, PREG_SET_ORDER | PREG_OFFSET_CAPTURE), $m]);
        $m = null;
        show('all-cols', [preg_match_all($p, $s, $m, PREG_PATTERN_ORDER | PREG_OFFSET_CAPTURE), $m]);
        show('split', preg_split($p, $s));
        show('replace', preg_replace($p, '<$0>', $s));
    }
}

$p = '/^(?:\+|-)?0o(?P<value>[0-7_]++)$/';
show('split-delim', preg_split($p, '0o755', -1, PREG_SPLIT_DELIM_CAPTURE | PREG_SPLIT_OFFSET_CAPTURE));
show('split-delim-empty', preg_split($p, '0o755', -1, PREG_SPLIT_NO_EMPTY | PREG_SPLIT_DELIM_CAPTURE));
show('split-miss', preg_split($p, '0o8', -1, PREG_SPLIT_DELIM_CAPTURE));
show('replace-named', preg_replace($p, '[$value]', '0o755'));
show('replace-offset', preg_replace($p, '[$0]', '0o755'));
$m = null;
show('offset', [preg_match($p, '0o755', $m, PREG_OFFSET_CAPTURE, 2), $m]);
show('offset-fail', [preg_match($p, '0o755', $m, 0, 2), $m]);
