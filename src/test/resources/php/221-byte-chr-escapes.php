<?php
$all = '';
$ok = true;
for ($i = 0; $i < 256; $i++) {
    $b = chr($i);
    $all .= $b;
    if (ord($b) !== $i || strlen($b) !== 1) { $ok = false; }
}
var_dump($ok, strlen($all), ord(''), ord(chr(-1)), ord(chr(256)));
echo bin2hex($all), "\n";
echo "raw:", $all, "\n";
var_dump("\xC3\xA9" === 'é', "\303\251" === 'é', "\u{1F600}" === '😀');
$h = <<<END
é\xFF\303\251\u{1F600}
END;
echo bin2hex($h), ':', strlen($h), "\n";
$n = <<<'END'
é\xFF\u{1F600}
END;
echo bin2hex($n), "\n";
