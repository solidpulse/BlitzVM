<?php
echo strtoupper('café'), ':', strtolower('CAFÉ'), ':', ucfirst('éabc'), ':', lcfirst('ÉABC'), "\n";
echo ucwords('café École foo-bar', " -"), "\n";
var_dump(stripos('éABC', 'a'), strripos('éABCabc', 'A'), stripos('É', 'é'), stristr('éABC', 'a'));
echo str_ireplace(['A', 'é'], ['x', 'Q'], 'aAÉé'), "\n";
var_dump(strcasecmp('café', 'CAFÉ') === 0, strncasecmp('Abé', 'aBé', 4), strnatcasecmp('éA2', 'éa2'));
$keys = array_change_key_case(['ÉA' => 1, 'éA' => 2], CASE_LOWER);
foreach ($keys as $k => $v) { echo bin2hex($k), ':', $v, "\n"; }
$upper = ''; $lower = '';
for ($i = 0; $i < 256; $i++) { $upper .= strtoupper(chr($i)); $lower .= strtolower(chr($i)); }
echo bin2hex($upper), "\n", bin2hex($lower), "\n";
foreach (['ctype_alpha', 'ctype_alnum', 'ctype_digit', 'ctype_upper', 'ctype_lower', 'ctype_space', 'ctype_xdigit',
          'ctype_punct', 'ctype_print', 'ctype_graph', 'ctype_cntrl'] as $fn) {
    $matched = '';
    for ($i = 0; $i < 256; $i++) { if ($fn(chr($i))) { $matched .= chr($i); } }
    echo $fn, ':', bin2hex($matched), "\n";
}
