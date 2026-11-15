<?php
echo "\x41\x42\x43|\101\102\103|\x7e|\176|\0end" === "ABC|ABC|~|~|" . chr(0) . "end" ? "ok bytes\n" : "bad bytes\n";
echo "\x41BC \101BC \x4 \1 \u{48}\u{49} \u{1F600}\n";
echo strlen("\0"), " ", strlen("\x00\x01"), " ", ord("\7"), " ", ord("\x7F"), "\n";
echo "\xC2\xA0" === "\u{A0}" ? "ok nbsp\n" : "bad nbsp\n";
echo bin2hex("\u{263A}"), " ", bin2hex("caf\u{E9}"), "\n";
echo "a\tb\\c\$d\"e\{f\q\n";
echo 'single \x41 \101 stays', "\n";
