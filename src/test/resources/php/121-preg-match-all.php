<?php
// preg_match_all: pattern order, set order, offsets, named groups, empty matches.
function show($label, $r, $m) {
    echo $label, ": ";
    var_dump($r);
    var_export($m);
    echo "\n";
}

show("pattern order", preg_match_all('/(\w)(\d)/', 'a1 b2 c3', $m), $m);
show("set order", preg_match_all('/(\w)(\d)/', 'a1 b2 c3', $m, PREG_SET_ORDER), $m);
show("no match", preg_match_all('/(x)(y)/', 'abc', $m), $m);
show("no match named", preg_match_all('/(?<n>x)(y)/', 'abc', $m), $m);
show("no match set order", preg_match_all('/(x)/', 'abc', $m, PREG_SET_ORDER), $m);
show("unmatched groups pattern order", preg_match_all('/(a)(b)?(c)?/', 'a ab abc', $m), $m);
show("unmatched groups set order", preg_match_all('/(a)(b)?(c)?/', 'a ab abc', $m, PREG_SET_ORDER), $m);
show("unmatched as null", preg_match_all('/(a)(b)?/', 'a ab', $m, PREG_UNMATCHED_AS_NULL), $m);
show("offset capture", preg_match_all('/o/', 'foo boo', $m, PREG_OFFSET_CAPTURE), $m);
show("set order + offsets", preg_match_all('/(o)(x)?/', 'oo', $m, PREG_SET_ORDER | PREG_OFFSET_CAPTURE), $m);
show("named pattern order", preg_match_all('/(?<key>\w+)=(?<val>\w*)/', 'a=1&b=&c=3', $m), $m);
show("named set order", preg_match_all('/(?<key>\w+)=(?<val>\w*)/', 'a=1&b=', $m, PREG_SET_ORDER), $m);
show("empty matches", preg_match_all('/x*/', 'axb', $m), $m);
show("offset arg", preg_match_all('/\d/', '1a2b3', $m, PREG_PATTERN_ORDER, 1), $m);
show("count only", preg_match_all('/a/', 'banana'), null);
show("anchored continues", preg_match_all('/\d/A', '123a45', $m), $m);
show("utf8", preg_match_all('/./u', 'aé😀', $m, PREG_OFFSET_CAPTURE), $m);
show("attributes", preg_match_all('/(\w+)="([^"]*)"/', '<a href="/x" title="T" data-id="7">', $m, PREG_SET_ORDER), $m);
