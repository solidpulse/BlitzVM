<?php
// mb_strtolower/mb_strtoupper: ASCII fast path, UTF-8 case mapping, invalid sequences and
// explicit encodings.
function show($label, $value) {
    echo $label, '=', bin2hex($value), "\n";
}

$strings = [
    'ASCII Only',
    'MiXeD 123',
    "caf\xC3\xA9",
    "CAF\xC3\x89",
    "\xC3\x80\xC3\x89\xC3\x8E\xC3\x95\xC3\x9C",
    "\xC3\xA0\xC3\xA9\xC3\xAE\xC3\xB5\xC3\xBC",
    "\xF0\x9F\x98\x80 ABC",
    "invalid\xFF\xFEtail",
    "truncated\xC3",
    '',
];

foreach ($strings as $i => $str) {
    show("lower$i", mb_strtolower($str));
    show("upper$i", mb_strtoupper($str));
}

show('utf8-explicit', mb_strtolower("CAF\xC3\x89", 'UTF-8'));
show('latin1', mb_strtolower("CAF\xC9", 'ISO-8859-1'));
show('latin1-upper', mb_strtoupper("caf\xE9", 'ISO-8859-1'));
show('utf16', mb_strtolower("\x00C\x00A\x00F\x00\xC9", 'UTF-16BE'));
show('ascii-encoding', mb_strtolower('ABC', 'ASCII'));

// Other mb functions share the same encoding lookup and UTF-8 decode path.
var_dump(mb_strlen('ASCII'), mb_strlen("caf\xC3\xA9"), mb_strlen("invalid\xFFtail"));
var_dump(mb_substr("caf\xC3\xA9 ok", 1, 3), bin2hex(mb_substr("caf\xC3\xA9 ok", -2)));
var_dump(mb_check_encoding('ASCII'), mb_check_encoding("caf\xC3\xA9"), mb_check_encoding("invalid\xFF"));
