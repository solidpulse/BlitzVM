<?php
$tests = [
    ["password", '$2y$10$abcdefghijklmnopqrstuv'],
    ["password", '$2y$04$abcdefghijklmnopqrstuv'],
    ["password", '$2a$10$abcdefghijklmnopqrstuv'],
    ["password", '$1$saltstri$'],
    ["password", '$1$'],
    ["password", '$1$abcdefghijklmnop$'],
    ["password", '$5$salt'],
    ["password", '$5$'],
    ["password", '$5$rounds=1000$salt'],
    ["password", '$5$rounds=5000$saltstring'],
    ["password", '$6$salt'],
    ["password", '$6$rounds=1000$saltstring'],
    ["", '$5$salt'],
    ["", '$1$salt'],
    ["a", '$6$salt'],
    ["longpassword-longpassword-longpassword", '$6$salt'],
    ["password", '$5$rounds=10$salt'],
    ["password", '$2y$10$abc'],
    ["password", '$2y$10$abcdefghijklmnopqrstu!'],
    ["password", ''],
    ["password", '*0'],
    ["password", '*1'],
    ["password", '$9$salt'],
];
foreach ($tests as [$password, $salt]) {
    echo json_encode($salt), " => ", json_encode(crypt($password, $salt)), "\n";
}
