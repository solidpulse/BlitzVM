<?php
// FILTER_VALIDATE_EMAIL
$emails = [
    "a@b.com",
    "user.name+tag@example.co.uk",
    "a@b",
    "a@localhost",
    "a b@c.com",
    "@b.com",
    "a@.com",
    "a@b..com",
    "a@b.c",
    "\"foo bar\"@example.com",
    "a@[127.0.0.1]",
    "a@b.com ",
    " a@b.com",
    "a@@b.com",
    "a@b_c.com",
];
foreach ($emails as $e) {
    echo json_encode($e), " => ", var_export(filter_var($e, FILTER_VALIDATE_EMAIL), true), "\n";
}

// FILTER_VALIDATE_IP with ranges
$ips = [
    "127.0.0.1", "8.8.8.8", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.1.1",
    "0.0.0.0", "255.255.255.255", "256.1.1.1", "01.2.3.4", "1.2.3",
    "::1", "::", "::ffff:127.0.0.1", "fd12:3456::1", "fe80::1", "2001:4860:4860::8888",
    "g::1", "1.2.3.4.5",
];
foreach ($ips as $ip) {
    echo str_pad($ip, 24), " plain=", var_export(filter_var($ip, FILTER_VALIDATE_IP), true),
        " v4=", var_export(filter_var($ip, FILTER_VALIDATE_IP, FILTER_FLAG_IPV4), true),
        " v6=", var_export(filter_var($ip, FILTER_VALIDATE_IP, FILTER_FLAG_IPV6), true),
        " priv=", var_export(filter_var($ip, FILTER_VALIDATE_IP, FILTER_FLAG_NO_PRIV_RANGE), true),
        " res=", var_export(filter_var($ip, FILTER_VALIDATE_IP, FILTER_FLAG_NO_RES_RANGE), true),
        "\n";
}
var_dump(filter_var("8.8.8.8", FILTER_VALIDATE_IP, FILTER_FLAG_IPV4 | FILTER_FLAG_NO_PRIV_RANGE | FILTER_FLAG_NO_RES_RANGE));
var_dump(filter_var("10.0.0.1", FILTER_VALIDATE_IP, FILTER_FLAG_IPV4 | FILTER_FLAG_NO_PRIV_RANGE | FILTER_FLAG_NO_RES_RANGE));

// FILTER_VALIDATE_INT
foreach (["0", "42", "-42", " 42", "42 ", "042", "0x2a", "1.0", "abc", "", true, false, null, 42, 42.9] as $v) {
    echo json_encode($v), " => ", var_export(filter_var($v, FILTER_VALIDATE_INT), true), "\n";
}
var_dump(filter_var("042", FILTER_VALIDATE_INT, FILTER_FLAG_ALLOW_OCTAL));
var_dump(filter_var("0x2a", FILTER_VALIDATE_INT, FILTER_FLAG_ALLOW_HEX));
var_dump(filter_var("5", FILTER_VALIDATE_INT, ["options" => ["min_range" => 2, "max_range" => 4]]));
var_dump(filter_var("5", FILTER_VALIDATE_INT, ["options" => ["default" => 7]]));
var_dump(filter_var("x", FILTER_VALIDATE_INT, ["options" => ["default" => 7]]));
var_dump(filter_var("5", FILTER_VALIDATE_INT, ["min_range" => 2]));

// FILTER_VALIDATE_BOOLEAN and FILTER_VALIDATE_FLOAT
foreach (["1", "0", "true", "false", "on", "off", "yes", "no", "", "TRUE", "2", "abc", true, false, null] as $v) {
    echo json_encode($v), ": ", var_export(filter_var($v, FILTER_VALIDATE_BOOLEAN), true), " ",
        var_export(filter_var($v, FILTER_VALIDATE_BOOLEAN, FILTER_NULL_ON_FAILURE), true), "\n";
}
foreach (["1", "1.5", "-1.5", ".5", "1e3", " 1.5", "1,5", "abc", "", true, false, 1.5] as $v) {
    echo json_encode($v), ": ", var_export(filter_var($v, FILTER_VALIDATE_FLOAT), true), "\n";
}

// FILTER_VALIDATE_URL
$urls = [
    "http://example.com", "https://example.com/path?x=1#f", "ftp://user:pass@host:21/path",
    "mailto:a@b.com", "file:///etc/passwd", "news:comp.lang.php", "example.com", "//example.com",
    "http://", "http:///path", "http://exa mple.com", "http://:80", "http://example.com:65536",
    "http://[::1]/", "javascript:alert(1)", "http://example.com/a%20b",
];
foreach ($urls as $u) {
    echo json_encode($u), " => ", var_export(filter_var($u, FILTER_VALIDATE_URL), true), "\n";
}
var_dump(filter_var("http://example.com", FILTER_VALIDATE_URL, FILTER_FLAG_PATH_REQUIRED));
var_dump(filter_var("http://example.com/x", FILTER_VALIDATE_URL, FILTER_FLAG_PATH_REQUIRED));
var_dump(filter_var("http://example.com/?x", FILTER_VALIDATE_URL, FILTER_FLAG_QUERY_REQUIRED));

// Default/raw conversion and array handling.
foreach (["abc", 5, true, null] as $v) {
    echo json_encode($v), ": ", var_export(filter_var($v, FILTER_DEFAULT), true), "\n";
}
var_dump(filter_var("<a>", FILTER_UNSAFE_RAW, FILTER_FLAG_STRIP_LOW));
var_dump(filter_var("x\xffy", FILTER_UNSAFE_RAW, FILTER_FLAG_STRIP_HIGH));
var_dump(filter_var("\"<>", FILTER_UNSAFE_RAW));
var_dump(filter_var("&", FILTER_UNSAFE_RAW, FILTER_FLAG_ENCODE_AMP));
var_dump(filter_var("", FILTER_UNSAFE_RAW, FILTER_FLAG_EMPTY_STRING_NULL));
var_dump(filter_var(["a", "1"], FILTER_VALIDATE_INT, FILTER_REQUIRE_ARRAY));
var_dump(filter_var("1", FILTER_VALIDATE_INT, FILTER_FORCE_ARRAY));
var_dump(filter_var(["a" => "1"], FILTER_VALIDATE_INT, ["flags" => FILTER_REQUIRE_ARRAY]));

// Sanitizers.
var_dump(filter_var("<a>&\"'", FILTER_SANITIZE_FULL_SPECIAL_CHARS));
var_dump(filter_var("<a>&\"'", FILTER_SANITIZE_SPECIAL_CHARS));
var_dump(filter_var("a1b2", FILTER_SANITIZE_NUMBER_INT));
var_dump(filter_var("a1b2-3.4e5", FILTER_SANITIZE_NUMBER_FLOAT, FILTER_FLAG_ALLOW_FRACTION | FILTER_FLAG_ALLOW_SCIENTIFIC));
var_dump(filter_var("a@b.com", FILTER_SANITIZE_EMAIL));
var_dump(filter_var("http://a b.com/", FILTER_SANITIZE_URL));
