<?php
$matrix = [
    [1, 2, 3],
    [4, 5, 6],
    [7, 8, 9]
];

$config = [
    "db" => [
        "host" => "localhost",
        "port" => 3306
    ],
    "app" => [
        "debug" => true
    ]
];

echo $matrix[1][1], "\n";
echo $matrix[2][0], "\n";
echo $config["db"]["host"], ":", $config["db"]["port"], "\n";
var_dump($config["app"]["debug"]);
