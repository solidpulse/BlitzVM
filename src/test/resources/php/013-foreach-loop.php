<?php
$fruits = ["apple", "banana", "cherry"];
foreach ($fruits as $fruit) {
    echo $fruit, "\n";
}

$user = [
    "name" => "Alice",
    "age" => 30,
    "role" => "developer"
];
foreach ($user as $k => $v) {
    echo $k, " = ", $v, "\n";
}
