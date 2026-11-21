<?php
set_error_handler(function (int $number, string $message): bool {
    echo 'WARN ', $message, "\n";
    return true;
});

$key = '0123456789abcdef0123456789abcdef';
$iv = 'abcdefghijkl';

var_dump(openssl_cipher_iv_length('aes-256-gcm'), openssl_cipher_key_length('aes-256-gcm'));
var_dump(openssl_cipher_iv_length('aes-256-cbc'), openssl_cipher_key_length('aes-256-cbc'));
var_dump(openssl_cipher_iv_length('nope-cipher'), openssl_cipher_key_length('nope-cipher'));

$tag = '';
$ciphertext = openssl_encrypt('hello world', 'aes-256-gcm', $key, OPENSSL_RAW_DATA, $iv, $tag);
var_dump(strlen($ciphertext), strlen($tag), bin2hex($ciphertext), bin2hex($tag));
var_dump(openssl_decrypt($ciphertext, 'aes-256-gcm', $key, OPENSSL_RAW_DATA, $iv, $tag));

$tampered = $ciphertext;
$tampered[0] = chr(ord($tampered[0]) ^ 1);
var_dump(openssl_decrypt($tampered, 'aes-256-gcm', $key, OPENSSL_RAW_DATA, $iv, $tag));
var_dump(openssl_error_string() !== false);

$cbc = openssl_encrypt('hello world', 'aes-256-cbc', $key, OPENSSL_RAW_DATA, 'abcdefghijklmnop');
var_dump(bin2hex($cbc));
var_dump(openssl_decrypt($cbc, 'aes-256-cbc', $key, OPENSSL_RAW_DATA, 'abcdefghijklmnop'));

$base64 = openssl_encrypt('hello world', 'aes-256-cbc', $key, 0, 'abcdefghijklmnop');
var_dump($base64);
var_dump(openssl_decrypt($base64, 'aes-256-cbc', $key, 0, 'abcdefghijklmnop'));

var_dump(openssl_encrypt('x', 'nope-cipher', $key, OPENSSL_RAW_DATA, $iv));
var_dump(OPENSSL_RAW_DATA, OPENSSL_ZERO_PADDING);
