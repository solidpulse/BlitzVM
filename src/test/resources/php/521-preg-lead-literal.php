<?php
// Patterns whose required literal follows a short lead of bounded width: the matcher jumps
// between occurrences of the literal, so leftmost-match order, offsets and anchors must
// still come out as PCRE's.
$cases = [
    ['/^[\s\r\n]?---[\s\r\n]?$/sm', "\n---\ntitle: x\n---\nbody --- not\n ---\n---x\n---"],
    ['/^[\s\r\n]?---[\s\r\n]?$/s', "---\nx"],
    ['/^[\s\r\n]?---[\s\r\n]?$/', "\n---"],
    ['/a?bc/', 'xbcabcaabc bc'],
    ['/[ab]{1,3}xyz/', 'cxyz bxyz abaxyz ababxyz'],
    ['/[ab]{2}xyz/', 'axyz abxyz babxyz'],
    ['/\d{1,2}--/', '1--22--333--x--'],
    ['/.?.?ab/', 'ab xab xxab xxxab'],
    ['/\bfoo-bar/', 'foo-bar xfoo-bar x foo-bar'],
    ['/(?<=x)?ab/', 'ab'],
    ['/\sab\.cd/', ' ab.cd xab.cd	ab.cd'],
    ['/[^a]?aaa/', 'aaaaaaa baaaa'],
    ['/x?aa/', 'aaaa xaaxaa'],
    ['/[\d]?12/', '1212 012 9912'],
    ['/a?b?abab/', 'ababab aabab'],
    ['/[a-z]?ÿÿ/', 'aÿÿ ÿÿÿ'],
    ['/.?€€/u', 'x€€ 😀€€ €€€'],
    ['/[^x]?😀😀/u', '😀😀😀 a😀😀'],
    ['/\p{L}?abc/u', 'éabc abc ßabc'],
    ['/^#?ab/m', "ab\n#ab\nx#ab"],
    ['/\Aab/', 'abab'],
    ['/[ab]?cd|ef/', 'cd ef acd'],
    ['/a{0,2}bcd/', 'bcd abcd aabcd aaabcd'],
    ['/a?bc(?=d)/', 'bcx abcd bcd'],
    ['/a?bc\b/', 'abcd abc bc'],
];
foreach ($cases as [$p, $s]) {
    $n = preg_match_all($p, $s, $m, PREG_OFFSET_CAPTURE);
    echo $p, ' => ', $n, ': ', json_encode($m[0], JSON_UNESCAPED_UNICODE), "\n";
    echo '  offset 3: ', preg_match($p, $s, $m1, PREG_OFFSET_CAPTURE, 3), ' ', json_encode($m1, JSON_UNESCAPED_UNICODE), "\n";
    echo '  replace: ', json_encode(preg_replace($p, '<$0>', $s), JSON_UNESCAPED_UNICODE), "\n";
    echo '  split: ', json_encode(preg_split($p, $s), JSON_UNESCAPED_UNICODE), "\n";
}
// The shape tempest's blog uses: front matter split from whole files.
$doc = PHP_EOL . "---\ntitle: Hello\ntags: [a, b]\n---\n\n# Heading\n\nText\n\n---\n\nMore\n";
var_dump(preg_split('/^[\s\r\n]?---[\s\r\n]?$/sm', $doc));
