<?php
try { json_encode("\xFF", JSON_THROW_ON_ERROR); } catch (JsonException $e) {
    echo $e->getCode(), ':', $e->getMessage(), "\n";
}
try { json_decode('"' . "\xFF" . '"', true, 512, JSON_THROW_ON_ERROR); } catch (JsonException $e) {
    echo $e->getCode(), ':', $e->getMessage(), "\n";
}
echo bin2hex(json_encode("\u{2028}\u{2029}😀", JSON_UNESCAPED_UNICODE)), "\n";
echo bin2hex(json_encode("\u{2028}\u{2029}😀", JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_LINE_TERMINATORS)), "\n";
var_dump(preg_replace('/./u', 'x', "\xFF"), preg_last_error(), preg_last_error_msg());
var_dump(preg_split('/./u', "\xFF"), preg_last_error());
echo bin2hex(preg_replace('/./', "\xFF", 'é')), "\n";
$matches = [];
preg_match_all('/(😀)?é/u', 'é😀é', $matches, PREG_SET_ORDER | PREG_OFFSET_CAPTURE | PREG_UNMATCHED_AS_NULL);
foreach ($matches as $m) { echo bin2hex($m[0][0]), '@', $m[0][1], ':', $m[1][0] === null ? 'null' : bin2hex($m[1][0]), '@', $m[1][1], "\n"; }
