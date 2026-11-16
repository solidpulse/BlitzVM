<?php
$value = ['é' => "😀e\u{0301}’", 'nul' => "\0"];
echo json_encode($value), "\n", json_encode($value, JSON_UNESCAPED_UNICODE), "\n";
var_dump(json_decode(json_encode($value), true) === $value);
echo bin2hex(json_decode('"\u00e9\ud83d\ude00"')), "\n";
foreach (["\xFF", "\xED\xA0\x80", "\xE2\x82", "a\xFFb"] as $s) {
    var_dump(json_encode($s), json_last_error(), json_last_error_msg());
    echo json_encode($s, JSON_INVALID_UTF8_SUBSTITUTE), ':', json_encode($s, JSON_INVALID_UTF8_IGNORE), "\n";
    echo bin2hex(json_encode($s, JSON_INVALID_UTF8_SUBSTITUTE | JSON_UNESCAPED_UNICODE)), "\n";
    var_dump(json_decode('"' . $s . '"'), json_last_error());
    echo bin2hex(json_decode('"' . $s . '"', true, 512, JSON_INVALID_UTF8_SUBSTITUTE)), "\n";
}
echo json_encode(["\xFF"], JSON_PARTIAL_OUTPUT_ON_ERROR), "\n";
