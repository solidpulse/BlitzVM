<?php
$name = "Alice";
$nums = [10, 20, 30];
$user = ["key" => "secret", "name" => "Bob"];

echo "Hello, $name!\n";
echo "Escapes:\tFirst line\nSecond line\n";
echo "First number: $nums[0]\n";
echo "User key: $user[key]\n";
echo "Braced: {$name}\n";
echo "Braced array: {$user['name']}\n";
