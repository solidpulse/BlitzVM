<?php
// Shared connection settings for the mysqli fixtures (env vars, else the test container).
function blitz_db_settings() {
    return [
        getenv('BLITZ_MYSQL_HOST') ?: '127.0.0.1',
        getenv('BLITZ_MYSQL_USER') ?: 'root',
        getenv('BLITZ_MYSQL_PASSWORD') !== false ? getenv('BLITZ_MYSQL_PASSWORD') : 'wp',
        getenv('BLITZ_MYSQL_DB') ?: 'blitz',
        (int) (getenv('BLITZ_MYSQL_PORT') ?: 33308),
    ];
}
function blitz_connect() {
    $s = blitz_db_settings();
    $host = $s[0]; $user = $s[1]; $pass = $s[2]; $db = $s[3]; $port = $s[4];
    mysqli_report(MYSQLI_REPORT_OFF);
    $link = mysqli_init();
    if (!mysqli_real_connect($link, $host, $user, $pass, $db, $port)) {
        echo "connect failed: ", mysqli_connect_errno(), "\n";
        exit(1);
    }
    return $link;
}
