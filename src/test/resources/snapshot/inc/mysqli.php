<?php
// Shared connection for the snapshot cases that need a live MariaDB. The cases only run when
// BLITZ_MYSQL_HOST is set, and they only use the database named by BLITZ_MYSQL_DB (blitz).
function snap_mysqli_link() {
    $host = getenv('BLITZ_MYSQL_HOST') ?: '127.0.0.1';
    $port = (int) (getenv('BLITZ_MYSQL_PORT') ?: 33307);
    $user = getenv('BLITZ_MYSQL_USER') ?: 'root';
    $password = getenv('BLITZ_MYSQL_PASSWORD') !== false ? getenv('BLITZ_MYSQL_PASSWORD') : 'wp';
    mysqli_report(MYSQLI_REPORT_ERROR | MYSQLI_REPORT_STRICT);
    $link = mysqli_init();
    mysqli_real_connect($link, $host, $user, $password, null, $port);
    return $link;
}
