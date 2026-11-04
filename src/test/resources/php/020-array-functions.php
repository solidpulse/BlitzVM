<?php
$user = [
    "id" => 101,
    "name" => "Charlie",
    "email" => "charlie@example.com",
    "role" => null
];

var_dump(isset($user["name"]));
var_dump(isset($user["role"]));
var_dump(isset($user["missing"]));

var_dump(array_key_exists("name", $user));
var_dump(array_key_exists("role", $user));
var_dump(array_key_exists("missing", $user));

unset($user["email"]);
var_dump(isset($user["email"]));

var_dump(in_array("Charlie", $user));
var_dump(in_array("admin", $user));

print_r(array_keys($user));
print_r(array_values($user));
