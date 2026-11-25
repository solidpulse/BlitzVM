<?php
// Fast path: the front matter delimiter split from spatie/yaml-front-matter.
function show($label, $value) {
    echo $label, ': ', var_export($value, true), "\n";
}

$pattern = '/^[\s\r\n]?---[\s\r\n]?$/sm';
$docs = [
    "\n---\ntitle: x\n---\nbody",
    "---\ntitle: x\n---\nbody",
    "\n---\ntitle: x\n---",
    "a\n---\nb",
    "a\n\n---\n\nb",
    "\n\n---\n\n",
    " --- \nx\n --- ",
    "---",
    "---\n",
    "\t---\r\n",
    "\r\n---\r\ntitle\r\n---\r\nx",
    "no delimiters here",
    '',
    "---\n\n---",
    "README --- not a delimiter\n----\n---x\n---",
];
foreach ($docs as $s) {
    echo "== ", bin2hex($s), "\n";
    $m = null;
    show('match', [preg_match($pattern, $s, $m, PREG_OFFSET_CAPTURE), $m, preg_last_error()]);
    foreach ([0, PREG_SPLIT_NO_EMPTY, PREG_SPLIT_DELIM_CAPTURE, PREG_SPLIT_OFFSET_CAPTURE,
                 PREG_SPLIT_NO_EMPTY | PREG_SPLIT_DELIM_CAPTURE | PREG_SPLIT_OFFSET_CAPTURE] as $flags) {
        foreach ([-1, 1, 2, 3] as $limit) {
            show("split-$flags-$limit", [preg_split($pattern, $s, $limit, $flags), preg_last_error()]);
        }
    }
    show('all', [preg_match_all($pattern, $s, $m, PREG_SET_ORDER | PREG_OFFSET_CAPTURE), $m]);
    show('replace', preg_replace($pattern, '<$0>', $s));
}

// A real front matter block, with the parts the library keeps.
$doc = "\n---\ntitle: Hello\ntags: [a, b]\n---\n\n# Heading\n\nText\n\n---\n\nMore\n";
show('parts', preg_split($pattern, $doc));
show('parts-noempty', preg_split($pattern, $doc, -1, PREG_SPLIT_NO_EMPTY));
