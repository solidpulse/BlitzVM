<?php
$pattern = '#^\/(?|foo(?|\/?$(*MARK:foo))|bar\/(?|(\d+)\/?$(*MARK:bar))|baz\/?$(*MARK:baz))#';

foreach (['/foo', '/bar/12', '/baz', '/nope'] as $uri) {
    $matches = [];
    $result = preg_match($pattern, $uri, $matches);
    echo $uri, ' => ', $result, ' ', json_encode($matches), "\n";
}

$matches = [];
preg_match('#^(?<first>a)(*MARK:first)$#', 'a', $matches);
var_dump($matches);
