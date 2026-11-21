<?php
// Literal prefixes: unanchored patterns jump to candidate start positions. These cases
// check that matches at the start, middle and end, overlapping matches, lookarounds and
// anchors behave exactly as PCRE does.

function show($label, $value) {
    echo $label, ': ', var_export($value, true), "\n";
}

function all($label, $pattern, $subject) {
    echo $label, ': ';
    echo preg_match_all($pattern, $subject, $m, PREG_OFFSET_CAPTURE), ' ';
    echo json_encode($m), "\n";
}

// No match at all, and matches at each end.
all('start', '/abc/', 'abcdef');
all('middle', '/abc/', 'xxabcxx');
all('end', '/abc/', 'xxabc');
all('none', '/abc/', 'ab');
all('twice', '/abc/', 'abc-abc');
all('overlap aa', '/aa/', 'aaaa');
all('overlap aba', '/aba/', 'ababa');

// Escaped literals and literal escapes.
all('escaped dot', '/a\.b/', 'xa.bx a.b');
all('hex literal', '/\x41bc/', 'xAbcx');
all('quoted', '/\Qa.b\E/', 'xa.bx a.b');
all('slash', '/a\/b/', 'xa/bx');

// Optional and quantified literals must not become a prefix that is too long.
all('optional b', '/ab?c/', 'ac abc abbc');
all('star b', '/ab*c/', 'ac abc abbc');
all('plus b', '/ab+c/', 'ac abc abbc');
all('range b', '/ab{0,2}c/', 'ac abc abbc');
all('exact b', '/ab{2}c/', 'abc abbc');
all('group quantified', '/(?:ab)+c/', 'abc ababc');
all('group plain', '/(ab)c/', 'xabc');
all('group plus then literal', '/(a+)bc/', 'aabc abc');
all('group range then literal', '/(a{2,})bc/', 'aaabc aabc');
all('group star then literal', '/(a*)bc/', 'aabc bc');
all('group digits then literal', '/(\d+)x/', '11x 1x');
all('group complete then literal', '/(ab)cd/', 'xabcd abcd');
all('branch', '/abc|abd/', 'xabd abc');
all('branch inside group', '/ab(?:c|d)/', 'xabd abc');

// Zero-width assertions before the literal are evaluated at each candidate.
all('word boundary', '/\bfoo/', 'foo xfoo foo');
all('non boundary', '/\Bfoo/', 'foo xfoo');
all('lookahead', '/(?=a)abc/', 'xabc abc');
all('negative lookahead', '/(?!x)abc/', 'xabc abc');
all('lookbehind', '/(?<=x)abc/', 'xabc abc');
all('negative lookbehind', '/(?<!x)abc/', 'xabc abc');
all('lookbehind slash', '/(?<=.)\/+/', 'x//y///z /');

// Anchors and \G keep their meaning.
all('caret', '/^abc/', "abc\nabc");
all('caret multiline', '/^abc/m', "x\nabc\nabc");
all('dollar multiline', '/abc$/m', "abc\nabc\nx");
all('anchored A', '/abc/A', 'xabc abc');
show('anchored offset', preg_match('/abc/A', 'xabc', $m, PREG_OFFSET_CAPTURE, 1));
all('G', '/\Gabc/', 'abcabcxabc');
show('G offset', preg_match('/\Gabc/', 'xabc', $m, 0, 1));

// preg_match offsets and preg_replace/preg_split use the same skipping.
show('match offset 1', preg_match('/abc/', 'xabcabc', $m, PREG_OFFSET_CAPTURE, 1));
show('match offset 4', preg_match('/abc/', 'xabcabc', $m, PREG_OFFSET_CAPTURE, 4));
show('match offset past', preg_match('/abc/', 'xabcabc', $m, 0, 6));
show('replace', preg_replace('/abc/', '-', 'abc-abc-xxabc'));
show('replace limit', preg_replace('/abc/', '-', 'abc-abc', 1));
show('split', preg_split('/abc/', 'abcxabcyabc', -1, PREG_SPLIT_OFFSET_CAPTURE));
show('split capture', preg_split('/(abc)/', 'xabcy', -1, PREG_SPLIT_DELIM_CAPTURE));

// A pattern whose first literal is followed by an optional atom has no usable prefix,
// but must still match at every position.
all('optional first', '/a?bcd/', 'abcd bcd xbcd');
all('star first', '/a*bcd/', 'aaabcd bcd');
all('class first', '/[ab]c/', 'xac xbc cc');
all('dot first', '/.abc/', 'xabc abcx');

// Long subject with sparse candidates, as in the WordPress filters.
$long = str_repeat('lorem ipsum dolor sit amet ', 100) . '<!-- wp:paragraph -->' . str_repeat(' consectetur ', 100);
all('long sparse', '/<!--\s+wp:([a-z]+)\s+-->/', $long);
show('long replace', substr(preg_replace('/<!--\s+wp:([a-z]+)\s+-->/', '[$1]', $long), 2900, 20));
