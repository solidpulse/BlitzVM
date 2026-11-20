<?php
// Shared PDO connection settings for the pdo-* fixtures.
require_once __DIR__ . '/inc_connect.php';

function blitz_pdo_dsn($extra = '') {
    $s = blitz_db_settings();
    return "mysql:host={$s[0]};port={$s[4]};dbname={$s[3]}{$extra}";
}

function blitz_pdo($options = null) {
    if ($options === null) {
        return new PDO(blitz_pdo_dsn(';charset=utf8mb4'), blitz_db_settings()[1], blitz_db_settings()[2]);
    }
    return new PDO(blitz_pdo_dsn(';charset=utf8mb4'), blitz_db_settings()[1], blitz_db_settings()[2], $options);
}

function blitz_show($label, $value) {
    echo $label, ': ', var_export($value, true), "\n";
}

function blitz_error($label, $fn) {
    try {
        blitz_show($label, $fn());
    } catch (Throwable $e) {
        echo $label, ': ', get_class($e), ' code=', var_export($e->getCode(), true),
            ' msg=', $e->getMessage();
        if ($e instanceof PDOException) {
            echo ' info=', var_export($e->errorInfo, true);
        }
        echo "\n";
    }
}
