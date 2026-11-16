<?php
$samples = [
    "",
    "abc",
    "a'b",
    'a"b',
    "a b",
    "a\nb",
    "a\$b",
    "back\\slash",
    "!@#%^&*()[]{};|<>?~`",
    "it's",
    "café",
];
foreach ($samples as $s) {
    echo bin2hex(escapeshellarg($s)), "\n";
}
foreach ($samples as $s) {
    echo bin2hex(escapeshellcmd($s)), "\n";
}
echo escapeshellcmd('a"b'), "\n";
echo escapeshellcmd('a"b"c'), "\n";
echo escapeshellcmd('"'), "\n";
echo escapeshellcmd("a'b'c"), "\n";
echo escapeshellcmd("a'b"), "\n";
try {
    escapeshellarg("a\0b");
} catch (Throwable $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
try {
    escapeshellcmd("a\0b");
} catch (Throwable $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
// Invalid bytes are dropped from both, as php's multibyte check does.
echo bin2hex(escapeshellarg("x\xffy")), " ", bin2hex(escapeshellcmd("x\xffy")), "\n";
