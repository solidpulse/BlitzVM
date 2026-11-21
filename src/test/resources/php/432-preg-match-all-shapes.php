<?php
// preg_match_all: counts with and without $matches, every flag, and the shapes of
// empty results.

function show($label, $value) {
    echo $label, ': ', var_export($value, true), "\n";
}

$text = 'a1 b2 c3';

// Without $matches only the count is produced.
show('count only', preg_match_all('/(\w)(\d)/', $text));
show('count only nomatch', preg_match_all('/(x)(y)/', $text));

// Pattern order is the default: one list per group.
show('pattern order', preg_match_all('/(\w)(\d)/', $text, $m));
show('pattern order explicit', preg_match_all('/(\w)(\d)/', $text, $m, PREG_PATTERN_ORDER));
show('pattern order offsets', preg_match_all('/(\w)(\d)/', $text, $m, PREG_OFFSET_CAPTURE));

// Set order: one array per match.
show('set order', preg_match_all('/(\w)(\d)/', $text, $m, PREG_SET_ORDER));
show('set order offsets', preg_match_all('/(\w)(\d)/', $text, $m, PREG_SET_ORDER | PREG_OFFSET_CAPTURE));
show('set order null groups', preg_match_all('/(a)(b)?(c)?/', 'a ab abc', $m, PREG_SET_ORDER | PREG_UNMATCHED_AS_NULL));

// No match with $matches: pattern order still gets one empty array per group.
show('nomatch pattern order', preg_match_all('/(x)(y)/', 'abc', $m));
show('nomatch pattern order value', $m);
show('nomatch set order', preg_match_all('/(x)(y)/', 'abc', $m, PREG_SET_ORDER));
show('nomatch set order value', $m);
show('nomatch offsets', preg_match_all('/(x)(y)/', 'abc', $m, PREG_OFFSET_CAPTURE));
show('nomatch offsets value', $m);

// Named groups appear both by name and by number.
show('named pattern order', preg_match_all('/(?<key>\w+)=(?<val>\w*)/', 'a=1&b=&c=3', $m));
show('named pattern order value', $m);
show('named set order', preg_match_all('/(?<key>\w+)=(?<val>\w*)/', 'a=1&b=', $m, PREG_SET_ORDER));
show('named set order value', $m);

// Empty matches and unmatched trailing groups.
show('empty matches', preg_match_all('/x*/', 'axb', $m));
show('empty matches value', $m);
show('trailing groups', preg_match_all('/(a)(b)?(c)?/', 'ac', $m));
show('trailing groups value', $m);
show('trailing groups null', preg_match_all('/(a)(b)?(c)?/', 'ac', $m, PREG_UNMATCHED_AS_NULL));
show('trailing groups null value', $m);

// The offset argument and anchoring.
show('offset arg', preg_match_all('/\d/', '1a2b3', $m, PREG_PATTERN_ORDER, 2));
show('offset arg value', $m);
show('negative offset', preg_match_all('/\d/', '1a2b3', $m, PREG_PATTERN_ORDER, -3));
