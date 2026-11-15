<?php
// preg_match: return values, $matches shapes, flags, offsets.
function show($label, $r, $m) {
    echo $label, ": ";
    var_dump($r);
    var_export($m);
    echo "\n";
}

show("simple", preg_match('/b+/', 'abbbc', $m), $m);
show("no match", preg_match('/x/', 'abc', $m), $m);
show("groups", preg_match('/(\d+)-(\d+)/', 'tel 555-1234 ok', $m), $m);
show("middle unmatched", preg_match('/(a)(b)?(c)/', 'ac', $m), $m);
show("trailing unmatched", preg_match('/(a)(b)?(c)?(d)?/', 'ac', $m), $m);
show("unmatched as null", preg_match('/(a)(b)?(c)?(d)?/', 'ac', $m, PREG_UNMATCHED_AS_NULL), $m);
show("offset capture", preg_match('/(a)(b)?(c)/', 'xxac', $m, PREG_OFFSET_CAPTURE), $m);
show("named", preg_match('/(?P<year>\d{4})-(?<month>\d\d)-(?\'day\'\d\d)/', 'on 2026-11-15.', $m), $m);
show("named underscore", preg_match('/(?<first_name>\w+) (?<last_name>\w+)?/', 'Ada ', $m), $m);
show("offset arg", preg_match('/\d/', 'a1b2c3', $m, 0, 2), $m);
show("negative offset", preg_match('/\d/', 'a1b2c3', $m, 0, -2), $m);
show("offset caret", preg_match('/^b/', 'ab', $m, 0, 1), $m);
show("lookbehind across offset", preg_match('/(?<=a)b/', 'ab', $m, 0, 1), $m);
show("case insensitive", preg_match('/HELLO/i', 'say hello', $m), $m);
show("dotall", preg_match('/a.b/s', "a\nb", $m), $m);
show("no dotall", preg_match('/a.b/', "a\nb", $m), $m);
show("multiline", preg_match_all('/^\w+$/m', "one\ntwo\nthree", $m), $m);
show("dollar before final newline", preg_match('/abc$/', "abc\n", $m), $m);
show("dollar end only", preg_match('/abc$/D', "abc\n", $m), $m);
show("anchored", preg_match('/b/A', 'ab', $m), $m);
show("anchored at offset", preg_match('/b/A', 'ab', $m, 0, 1), $m);
show("hash delimiter", preg_match('#/path/(\w+)#', '/path/to', $m), $m);
show("brace delimiters", preg_match('{a(b)c}', 'xabcx', $m), $m);
show("bracket delimiters", preg_match('[a\[b]', 'a[b', $m), $m);
show("paren delimiters with nesting", preg_match('(a(b)c)', 'abc', $m), $m);
show("escaped delimiter", preg_match('/a\/b/', 'a/b', $m), $m);
show("without matches arg", preg_match('/z/', 'xyz'), null);
show("empty match", preg_match('/x*/', 'abc', $m), $m);
show("alternation groups", preg_match('/(cat)|(dog)/', 'hotdog', $m), $m);
show("backref", preg_match('/(\w)\1/', 'hello', $m), $m);
show("named backref", preg_match('/(?P<q>["\']).*?(?P=q)/', 'say "hi" now', $m), $m);
show("k backref", preg_match('/(?<q>[\'"])x\k<q>/', "'x'", $m), $m);
show("g backref", preg_match('/(a)(b)\g{-1}\g1/', 'abba', $m), $m);
show("utf8 offsets", preg_match('/(ö)(b)/u', 'aöb', $m, PREG_OFFSET_CAPTURE), $m);
show("utf8 offset arg", preg_match('/b/u', 'ööb', $m, PREG_OFFSET_CAPTURE, 4), $m);
show("possessive", preg_match('/a++b/', 'aaab', $m), $m);
show("possessive fail", preg_match('/a++a/', 'aaaa', $m), $m);
show("atomic", preg_match('/(?>a+)b/', 'aab', $m), $m);
show("literal braces", preg_match('/{(\w+)}/', 'x {name} y', $m), $m);
show("brace not quantifier", preg_match('/a{,2}/', 'a{,2}', $m), $m);
show("quantifier", preg_match('/^a{2,3}$/', 'aaa', $m), $m);
