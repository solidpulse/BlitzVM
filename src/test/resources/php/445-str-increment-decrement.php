<?php
foreach (['a', 'z', 'A', 'Z', 'Az', 'zz', 'a9', '9', 'Zz', '99', 'z9', '9z', 'a99', 'A9', '0z', 'zz9', '00'] as $value) {
    echo $value, ' inc=', str_increment($value), "\n";
}

foreach (['b', 'aa', 'AA', 'Ba', 'b0', '10', 'a0', 'a1', 'A0', 'aa0', '100', '1', 'aaa', '99', '999', '1a', '1z', 'z0', 'aA0'] as $value) {
    echo $value, ' dec=', str_decrement($value), "\n";
}

foreach (['0', '00', '09', 'a', 'A', '0a'] as $value) {
    try {
        str_decrement($value);
    } catch (ValueError $e) {
        echo $e->getMessage(), "\n";
    }
}

foreach (['', 'a-b', 'a b'] as $value) {
    foreach (['str_increment', 'str_decrement'] as $function) {
        try {
            $function($value);
        } catch (ValueError $e) {
            echo $e->getMessage(), "\n";
        }
    }
}
