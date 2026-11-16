<?php
foreach (["\xFF", "\xE2\x82", "\xF0\x9F\x98", "\xC0\x80", "\xED\xA0\x80", "\xF4\x90\x80\x80", "a\xE2\x82X"] as $s) {
    echo bin2hex($s), ':', mb_strlen($s), ':', bin2hex(mb_scrub($s)), ':';
    var_dump(mb_check_encoding($s, 'UTF-8'));
}
var_dump(mb_check_encoding(['é' => ['😀', "\xFF"]]), mb_check_encoding("\xE9", 'ISO-8859-1'));
var_dump(mb_internal_encoding(), mb_substitute_character());
mb_substitute_character(0xFFFD);
echo bin2hex(mb_scrub("a\xFFb")), "\n";
mb_substitute_character('none');
echo bin2hex(mb_scrub("a\xFFb")), "\n";
mb_substitute_character(63);
mb_internal_encoding('ISO-8859-1');
var_dump(mb_strlen("\xE9"), mb_ord("\xE9"), bin2hex(mb_chr(233)));
mb_internal_encoding('UTF-8');
foreach (['ISO-8859-1', 'Windows-1252', 'ASCII', 'UTF-16', 'UTF-16LE', 'UTF-16BE'] as $enc) {
    $converted = mb_convert_encoding('é€😀', $enc, 'UTF-8');
    echo $enc, ':', bin2hex($converted), ':', mb_convert_encoding($converted, 'UTF-8', $enc), "\n";
}
echo bin2hex(mb_convert_encoding("\x80\xE9", 'UTF-8', 'Windows-1252')), "\n";
var_dump(mb_detect_encoding('abc'), mb_detect_encoding('é', ['ASCII', 'UTF-8']), mb_detect_encoding("\xFF", ['ASCII', 'UTF-8'], true));
mb_detect_order(['UTF-8', 'ISO-8859-1']);
var_dump(mb_detect_order(), mb_detect_encoding("\xFF"), in_array('UTF-8', mb_list_encodings(), true));
