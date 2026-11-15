<?php
$paths = [
    "", "/", "//", "///", ".", "..", "./",
    "/a", "/a/", "/a/b", "/a/b/",
    "a/b", "a/b/", "a", "a/",
    "/a/b/c.php", "foo.php", "/foo.php/", "/a//b/"
];
foreach ($paths as $p) {
    echo json_encode($p), "|", dirname($p), "|", basename($p), "\n";
}

// dirname levels
var_dump(
    dirname("/a/b/c", 1),
    dirname("/a/b/c", 2),
    dirname("/a/b/c", 3),
    dirname("/a/b/c", 4),
    dirname("a/b", 2),
    dirname("a/b", 3),
    dirname("", 2)
);

// basename suffix
var_dump(
    basename("/a/b.php", ".php"),
    basename("/a/b.php", ".txt"),
    basename("/a/.php", ".php"),
    basename(".php", ".php"),
    basename("foo.php", "foo.php"),
    basename("/a/b.php/", ".php")
);

// file_exists, is_file, is_dir, is_readable, realpath
var_dump(
    file_exists(__FILE__),
    is_file(__FILE__),
    is_dir(__FILE__),
    is_readable(__FILE__),
    realpath(__FILE__) === __FILE__
);

var_dump(
    file_exists(__DIR__),
    is_file(__DIR__),
    is_dir(__DIR__),
    is_readable(__DIR__),
    realpath(__DIR__) === __DIR__
);

$missing = __DIR__ . '/definitely_does_not_exist_12345.php';
var_dump(
    file_exists($missing),
    is_file($missing),
    is_dir($missing),
    is_readable($missing),
    realpath($missing)
);

var_dump(
    file_exists(""),
    is_file(""),
    is_dir(""),
    is_readable(""),
    realpath(".") === dirname(__DIR__, 4)
);

// Relative path via CWD
var_dump(
    file_exists("src/test/resources/php/inc/no_return.php"),
    is_file("src/test/resources/php/inc/no_return.php"),
    is_dir("src/test/resources/php/inc")
);
