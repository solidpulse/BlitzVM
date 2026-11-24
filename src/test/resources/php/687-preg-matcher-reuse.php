<?php
// preg calls reuse a matcher per pattern: a nested call from a replace callback must not
// disturb the outer walk, and a call after a pattern error must still see the whole subject.
class Replacer
{
    public function __toString(): string
    {
        preg_match('/(\w+)-(\w+)/', 'nested-match', $m);
        return strtoupper($m[1] ?? 'none');
    }
}

function show($label, $value) {
    echo $label, '=', json_encode($value), "\n";
}

show('match', preg_match('/(\w+)-(\w+)/', 'one-two', $m) . ':' . ($m[2] ?? ''));
show('match-again', preg_match('/(\w+)-(\w+)/', 'three-four', $m) . ':' . ($m[1] ?? ''));
show('offset', preg_match('/(\w+)-(\w+)/', 'xx-one-two', $m, 0, 2) . ':' . ($m[0] ?? ''));
show('all', preg_match_all('/\d+/', 'a1b22c333', $all) . ':' . json_encode($all[0]));
show('all-set-order', preg_match_all('/(a)(b)/', 'abab', $set, PREG_SET_ORDER) . ':' . json_encode($set));

// A callback that runs the same pattern nested while the outer replacement is in flight.
$nested = preg_replace_callback('/(\w+)-(\w+)/', function ($m) {
    preg_match('/(\w+)-(\w+)/', 'inner-one', $inner);
    return $m[1] . '/' . $m[2] . '/' . $inner[2];
}, 'a-b c-d');
show('nested-callback', $nested);

// The same pattern in a second, non-nested call after the nested one.
show('after-nested', preg_replace('/(\w+)-(\w+)/', '$2:$1', 'e-f g-h'));

// preg_replace with a replacement object that runs preg itself.
show('object-replacement', preg_replace('/x/', new Replacer(), 'x-x'));

// Pattern errors leave the next successful call on the whole subject.
show('error', @preg_match('/[/', 'subject'));
show('after-error', preg_match('/b/', 'abcb', $m) . ':' . ($m[0] ?? ''));

// Splits, including offsets and captured delimiters, interleaved with another pattern.
show('split', preg_split('/,/', 'a,b,,c', -1, PREG_SPLIT_NO_EMPTY));
show('split-delim', preg_split('/(-)/', 'a-b-c', -1, PREG_SPLIT_DELIM_CAPTURE));
show('split-offsets', preg_split('/,/', 'a,bb,c', -1, PREG_SPLIT_OFFSET_CAPTURE));
show('split-utf', preg_split('/,/u', "é,x,ü"));

// \\R matches across lines that were split by a previous call, and a prefix pattern after a
// non-prefix one must not inherit the old region.
$lines = preg_split('/\R/', "one\ntwo\r\nthree");
show('lines', $lines);
show('prefix', preg_match('/two/', $lines[1] ?? '') . ':' . preg_match('/^t/', 'ttt'));
show('grep', preg_grep('/^\d+$/', ['1', 'a', '22', 'b3']));
show('grep-invert', preg_grep('/^\d+$/', ['1', 'a', '22'], PREG_GREP_INVERT));
show('nomatch', preg_match('/zzz/', 'abc', $m) . ':' . json_encode($m));
show('empty-subject', preg_match('/^$/', '', $m) . ':' . ($m[0] ?? 'none'));
show('anchors', preg_match('/^b$/', 'b', $m) . ':' . preg_match('/^b$/', 'ab', $m2));
show('transparent', preg_match('/\bword\b/', 'a word here', $m) . ':' . ($m[0] ?? ''));
