<?php
$host = getenv('BLITZ_MYSQL_HOST');
if ($host === false || $host === '') {
    return;
}
$port = getenv('BLITZ_MYSQL_PORT');
$user = getenv('BLITZ_MYSQL_USER');
$password = getenv('BLITZ_MYSQL_PASSWORD');
$dsn = 'mysql:host=' . $host . ($port === false || $port === '' ? '' : ';port=' . $port);
$pdo = new PDO($dsn, $user === false ? '' : $user, $password === false ? '' : $password);
