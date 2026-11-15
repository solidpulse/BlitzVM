<?php
// Hashes made by real PHP must verify here.
$h1 = '$2y$04$swz6C2xnOzzZY4BIHzsX8exjpplpHMfwjk00D9Bci62pNTsyXnngG';
$h2 = '$2y$05$TsdVmCqaLqE0X0OebXNlAOujM1V.kjzDH6XWUvBP8kLwQierBizs6';
$h3 = '$2y$04$frkXqLRDAU4ymqdd2QKVYu17Xr1PhHpjeIyHa59UQbeJDL9fLY4aG';
$h4 = '$2a$04$abcdefghijklmnopqrstuuCi15uRb1eH7NAlJ/TgeJertyknQpYn2';
var_dump(password_verify('correct horse', $h1), password_verify('wrong', $h1));
var_dump(password_verify('pässwörd ünïcode', $h2), password_verify('passwörd ünïcode', $h2));
var_dump(password_verify(str_repeat('x', 72) . 'different tail', $h3));
var_dump(password_verify('abc', $h4));
var_dump(password_get_info($h1), password_get_info('nonsense'));
var_dump(password_needs_rehash($h1, PASSWORD_DEFAULT), password_needs_rehash($h1, PASSWORD_BCRYPT, ['cost' => 4]), password_needs_rehash('x', PASSWORD_DEFAULT));
// Our own hashes
$mine = password_hash('secret', PASSWORD_DEFAULT, ['cost' => 4]);
var_dump(strlen($mine), substr($mine, 0, 7), password_verify('secret', $mine), password_verify('Secret', $mine));
var_dump(password_hash('a', PASSWORD_BCRYPT, ['cost' => 4]) !== password_hash('a', PASSWORD_BCRYPT, ['cost' => 4]));
var_dump(PASSWORD_DEFAULT, PASSWORD_BCRYPT, in_array('2y', password_algos()));

// zlib: our bytes must equal PHP's (compared through base64_decode of PHP's output, since
// bin2hex and base64_encode of binary strings are not exact until strings are byte strings)
$text = 'Hello, Hello, Hello, Hello, compressed world!';
var_dump(gzdeflate($text) === base64_decode('80jNycnXUfDARiXn5xYUpRYXp6YolOcX5aQoAgA='));
var_dump(gzcompress($text) === base64_decode('eJzzSM3JyddR8MBGJefnFhSlFhenpiiU5xflpCgCAF3KD58='));
var_dump(gzencode($text) === base64_decode('H4sIAAAAAAAAA/NIzcnJ11HwwEYl5+cWFKUWF6emKJTnF+WkKAIAZkpS0C0AAAA='));
var_dump(gzdeflate($text, 9) === base64_decode('80jNycnXUfDARiXn5xYUpRYXp6YolOcX5aQoAgA='));
var_dump(gzcompress($text, 1) === base64_decode('eAHzSM3JyddR8MBGJefnFhSlFhenpiiU5xflpCgCAF3KD58='));
var_dump(gzencode($text, 9) === base64_decode('H4sIAAAAAAACA/NIzcnJ11HwwEYl5+cWFKUWF6emKJTnF+WkKAIAZkpS0C0AAAA='));
var_dump(zlib_encode($text, ZLIB_ENCODING_DEFLATE) === base64_decode('eJzzSM3JyddR8MBGJefnFhSlFhenpiiU5xflpCgCAF3KD58='));
var_dump(gzencode('') === base64_decode('H4sIAAAAAAAAAwMAAAAAAAAAAAA='));
var_dump(gzcompress(str_repeat('abc', 50), 6) === base64_decode('eJxLTEpOHHwIAPB8OW0='));
var_dump(gzdeflate($text, -1, ZLIB_ENCODING_GZIP) === gzencode($text));
// decoding PHP's output and round trips
var_dump(gzinflate(base64_decode('80jNycnXUfDARiXn5xYUpRYXp6YolOcX5aQoAgA=')));
var_dump(gzuncompress(base64_decode('eJxLTEpOHHwIAPB8OW0=')) === str_repeat('abc', 50));
var_dump(gzdecode(base64_decode('H4sIAAAAAAAAA/NIzcnJ11HwwEYl5+cWFKUWF6emKJTnF+WkKAIAZkpS0C0AAAA=')));
var_dump(gzinflate(gzdeflate($text)), gzuncompress(gzcompress($text)), gzdecode(gzencode($text)));
var_dump(zlib_decode(gzencode($text)), zlib_decode(gzcompress($text)), zlib_decode(gzdeflate($text)));
$big = str_repeat("The quick brown fox jumps over the lazy dog. ", 2000) . 'é ü 漢字';
var_dump(gzuncompress(gzcompress($big)) === $big, gzinflate(gzdeflate($big, 1)) === $big, gzdecode(gzencode($big, 9)) === $big);
var_dump(gzdecode(gzencode('')));
var_dump(ZLIB_ENCODING_RAW, ZLIB_ENCODING_GZIP, ZLIB_ENCODING_DEFLATE, FORCE_GZIP, FORCE_DEFLATE);
var_dump(base64_decode('aGVsbG8gd29ybGQ'), base64_decode("aGVs bG8=\n"), base64_decode('###', true));
