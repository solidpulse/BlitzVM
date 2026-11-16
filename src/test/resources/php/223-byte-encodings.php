<?php
$s = "é😀\xFF\0+ %";
echo base64_encode($s), "\n", bin2hex(base64_decode(base64_encode($s), true)), "\n";
echo urlencode($s), "\n", rawurlencode($s), "\n";
var_dump(urldecode(urlencode($s)) === $s, rawurldecode(rawurlencode($s)) === $s);
echo bin2hex(urldecode('%FF%C3%A9+%00')), ':', bin2hex(rawurldecode('%FF+%zz')), "\n";
$qp = quoted_printable_encode($s . "\r\ntrailing \r\n");
echo $qp, "\n", bin2hex(quoted_printable_decode($qp)), "\n";
echo bin2hex(quoted_printable_decode("=FF=00=C3=A9=\r\nend")), "\n";
var_dump(hex2bin(bin2hex($s)) === $s, strlen(uniqid('é')), strlen(uniqid('é', true)));
foreach (['Z', 'Zg', 'Zg=', 'Zg==', 'Zg===', 'Z=g=', 'Zm9v ', "\xFFZm9v"] as $v) {
    foreach ([false, true] as $strict) {
        $decoded = base64_decode($v, $strict);
        echo $decoded === false ? 'false' : bin2hex($decoded), ':';
    }
    echo "\n";
}
$all = ''; for ($i = 0; $i < 256; $i++) { $all .= chr($i); }
echo hash('sha256', quoted_printable_encode($all)), "\n";
var_dump(quoted_printable_decode(quoted_printable_encode($all)) === $all);
