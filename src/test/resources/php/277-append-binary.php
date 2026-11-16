<?php
function binary_append() {
    $s = "\0\xff"; $s .= "\x80\xc3\xa9";
    $copy = $s; $s .= "\0";
    echo strlen($s), '|', bin2hex($s), '|', bin2hex($copy), "\n";
    $s[1] = "\xfe"; $s .= "\xff";
    echo bin2hex($s), '|', base64_encode($s), "\n";
    var_dump($s === "\0\xfe\x80\xc3\xa9\0\xff");
    $s = ''; $s .= '0'; $s .= ''; var_dump($s, empty($s));
}
binary_append();
