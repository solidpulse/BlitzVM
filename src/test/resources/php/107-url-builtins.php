<?php
$s = "a b+c/d?e=f&g~h*i'j(k)!l.m-n_o é";
var_dump(urlencode($s), rawurlencode($s));
var_dump(urldecode("a+b%20c%2Fd%zz%4"), rawurldecode("a+b%20c%C3%A9"), urldecode(urlencode($s)) === $s, rawurldecode(rawurlencode($s)) === $s);

$urls = [
    "http://user:pass@example.com:8080/path/to/page.php?query=1&b[]=2#frag",
    "https://example.com",
    "https://example.com/",
    "//example.com/path",
    "/just/a/path?x=1",
    "relative/path.html",
    "example.com",
    "example.com:80",
    "example.com:80/path",
    "mailto:someone@example.com",
    "file:///etc/passwd",
    "http://[::1]:8080/x",
    "http://example.com?",
    "http://example.com#",
    "http://example.com/?#",
    "http://user@host",
    "http:///example.com",
    "http://example.com:99999",
    "http://:80",
    "",
    "?q=1",
    "#top",
    "https://wordpress.org/latest.tar.gz?ver=7.1",
    "http://localhost/wp-admin/admin-ajax.php?action=heartbeat",
    "urn:isbn:0451450523",
    "a:65535",
    "http://h:0/",
];
foreach ($urls as $u) {
    echo json_encode($u), " => ";
    var_export(parse_url($u));
    echo "\n";
}
$u = "http://user:pass@example.com:8080/p?q=1#f";
var_dump(parse_url($u, PHP_URL_SCHEME), parse_url($u, PHP_URL_HOST), parse_url($u, PHP_URL_PORT), parse_url($u, PHP_URL_USER),
    parse_url($u, PHP_URL_PASS), parse_url($u, PHP_URL_PATH), parse_url($u, PHP_URL_QUERY), parse_url($u, PHP_URL_FRAGMENT),
    parse_url("/x", PHP_URL_HOST), parse_url("http:///x", PHP_URL_HOST));

$data = ["foo" => "bar", "baz" => "boom", "cow" => "milk", "php" => "hypertext processor", "n" => null, "t" => true, "f" => false,
    "nested" => ["a" => 1, "b" => [2, 3], "c d" => "e f"], 0 => "zero", 5 => "five", "pi" => 3.25, "empty" => ""];
var_dump(http_build_query($data));
var_dump(http_build_query($data, "p_"));
var_dump(http_build_query($data, "", "&amp;"));
var_dump(http_build_query(["a b" => "c d~"], "", "&", PHP_QUERY_RFC3986));
var_dump(http_build_query([]), http_build_query(["x" => []]));
