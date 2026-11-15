<?php
$inputs = ["", "abc", "The quick brown fox jumps over the lazy dog", "héllo wörld", str_repeat("x", 1000)];
$algos = ["md5", "sha1", "sha224", "sha256", "sha384", "sha512/224", "sha512/256", "sha512", "sha3-224", "sha3-256", "sha3-384", "sha3-512", "crc32b", "crc32c"];
foreach ($algos as $algo) {
    foreach ($inputs as $in) {
        echo $algo, " ", hash($algo, $in), "\n";
    }
}
echo hash("SHA256", "case"), "\n";

$keys = ["", "key", str_repeat("k", 200)];
foreach (["md5", "sha1", "sha256", "sha384", "sha512", "sha3-256"] as $algo) {
    foreach ($keys as $key) {
        echo "hmac ", $algo, " ", hash_hmac($algo, "The quick brown fox jumps over the lazy dog", $key), "\n";
    }
}

var_dump(hash_equals("abc", "abc"), hash_equals("abc", "abd"), hash_equals("abc", "abcd"), hash_equals("", ""));
var_dump(hash_equals(hash("sha256", "x", true), hash("sha256", "x", true)));
var_dump(in_array("sha256", hash_algos()), in_array("crc32b", hash_algos()), in_array("crc32b", hash_hmac_algos()), in_array("sha512", hash_hmac_algos()));
var_dump(md5("abc") === hash("md5", "abc"), sha1("abc") === hash("sha1", "abc"));
var_dump(hex2bin("48656c6c6f20576f726c64"), hex2bin(""), hex2bin(bin2hex("héllo")) === "héllo", hex2bin("6A6b"));
