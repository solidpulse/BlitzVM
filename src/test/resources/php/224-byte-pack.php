<?php
echo bin2hex(pack('C*', 0, 127, 128, 195, 169, 255)), "\n";
echo bin2hex(pack('a*', 'é😀')), ':', bin2hex(pack('A8Z8', 'é', 'é')), "\n";
echo bin2hex(pack('nNvV', 0xCAFE, 0x12345678, 0xCAFE, 0x12345678)), "\n";
echo bin2hex(pack('H*h*', '00c3a9ff', '0123')), "\n";
var_dump(unpack('C*', "\0\x80\xFF"));
var_dump(unpack('nfirst/Nsecond/vthird/Vfourth', pack('nNvV', 0xCAFE, 0x12345678, 0xCAFE, 0x12345678)));
var_dump(unpack('a2text/H*hex', "é\0\xFF"));
var_dump(unpack('gfloat/edouble', pack('ge', 1.5, 2.25)));
echo bin2hex(pack('C2x2C@2C', 1, 2, 3, 4)), "\n";
$all = ''; for ($i = 0; $i < 256; $i++) { $all .= chr($i); }
$n = 0; $ok = true;
foreach (unpack('C*', $all) as $v) { if ($v !== $n) { $ok = false; } $n++; }
var_dump($ok, $n, pack('a*', $all) === $all);
