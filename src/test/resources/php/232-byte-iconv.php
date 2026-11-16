<?php
echo bin2hex(iconv('ISO-8859-1', 'UTF-8', "caf\xE9")), "\n";
echo bin2hex(iconv('UTF-8', 'UTF-16LE', 'é😀')), "\n";
echo iconv('UTF-8', 'ASCII//TRANSLIT', 'café'), ':', iconv('UTF-8', 'ASCII//IGNORE', 'éa😀b'), "\n";
echo bin2hex(iconv('UTF-8', 'UTF-8//IGNORE', "a\xFFb")), "\n";
var_dump(iconv_strlen('é😀a'), iconv_strpos('é😀a', 'a'), iconv_substr('é😀a', 1, 1));
echo bin2hex(@utf8_encode("caf\xE9")), ':', bin2hex(@utf8_decode('café😀')), "\n";
echo @mb_convert_encoding('é€😀', 'HTML-ENTITIES', 'UTF-8'), "\n";
echo bin2hex(@mb_convert_encoding('&eacute;&#128512;', 'UTF-8', 'HTML-ENTITIES')), "\n";
mb_internal_encoding('ISO-8859-1');
var_dump(iconv_strlen('é😀a'), iconv_strpos('é😀a', 'a'));
mb_internal_encoding('UTF-8');
