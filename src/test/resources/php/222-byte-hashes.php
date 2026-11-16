<?php
$s = "café😀\0\xFF";
foreach (['md5', 'sha1', 'sha256', 'sha384', 'sha512', 'crc32b'] as $algo) {
    $raw = hash($algo, $s, true);
    echo $algo, ':', strlen($raw), ':', bin2hex($raw), "\n";
}
echo bin2hex(md5($s, true)), ':', bin2hex(sha1($s, true)), ':', crc32($s), "\n";
$pw = "päss’😀\xFF";
$pre = base64_encode(hash_hmac('sha384', $pw, 'wp-sha384', true));
echo $pre, ':', strlen($pre), "\n";
var_dump(hash_equals(hash('sha256', $s, true), hex2bin(hash('sha256', $s))));
echo bin2hex(sodium_crypto_generichash($s)), "\n";
$b = random_bytes(64);
var_dump(strlen($b), bin2hex(base64_decode(base64_encode($b))) === bin2hex($b));
var_dump(random_int(7, 7), random_int(PHP_INT_MIN, PHP_INT_MAX) >= PHP_INT_MIN);
$hash = password_hash($pre, PASSWORD_BCRYPT, ['cost' => 4]);
var_dump(password_verify($pre, $hash), password_verify('wrong', $hash));
$ctx = hash_init('sha256');
var_dump(is_object($ctx), get_class($ctx), is_resource($ctx));
hash_update($ctx, substr($s, 0, 3));
$copy = hash_copy($ctx);
hash_update($ctx, substr($s, 3));
hash_update($copy, 'é😀');
var_dump(hash_final($ctx, true) === hash('sha256', $s, true), hash_final($copy) === hash('sha256', substr($s, 0, 3) . 'é😀'));
$ctx = hash_init('sha384', HASH_HMAC, "é\xFF");
hash_update($ctx, $s);
var_dump(hash_final($ctx, true) === hash_hmac('sha384', $s, "é\xFF", true));
echo hash_pbkdf2('sha256', "é\xFF", '😀', 2, 33), "\n";
echo bin2hex(hash_pbkdf2('sha256', "é\xFF", '😀', 2, 17, true)), "\n";
echo bin2hex(hash_hkdf('sha256', "é\xFF", 42, '😀', "\x80")), "\n";
