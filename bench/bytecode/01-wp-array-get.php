<?php
// WordPress wp-includes/functions.php:5169, copied without changing its body.
function _wp_array_get( $input_array, $path, $default_value = null ) {
    if ( ! is_array( $path ) || 0 === count( $path ) ) {
        return $default_value;
    }
    foreach ( $path as $path_element ) {
        if ( ! is_array( $input_array ) ) {
            return $default_value;
        }
        if ( is_string( $path_element )
            || is_int( $path_element )
            || null === $path_element
        ) {
            if ( isset( $path_element, $input_array[ $path_element ] ) ) {
                $input_array = $input_array[ $path_element ];
                continue;
            }
            if ( isset( $path_element ) && array_key_exists( $path_element, $input_array ) ) {
                $input_array = $input_array[ $path_element ];
                continue;
            }
        }
        return $default_value;
    }
    return $input_array;
}

function benchmark() {
    $data = ['a' => ['b' => 7, 'c' => ['d' => 11], 'null' => null]];
    $sum = 0;
    for ($i = 0; $i < 300000; $i = $i + 1) {
        $sum = $sum + _wp_array_get($data, ['a', 'b'], 0);
        $sum = $sum + _wp_array_get($data, ['a', 'c', 'd'], 0);
    }
    return $sum;
}

// Extra untimed success/default/invalid-path checks, including default arguments.
$check = ['a' => ['nil' => null, 'v' => 3]];
echo _wp_array_get($check, ['a', 'v']), ':',
    _wp_array_get($check, ['a', 'nil'], 99) === null, ':',
    _wp_array_get($check, ['a', 'missing'], 99), ':',
    _wp_array_get($check, [], 99), ':',
    _wp_array_get($check, 'invalid', 99), ':',
    _wp_array_get($check, ['a', 'v', 'deeper'], 99), "\n";
for ($round = 0; $round < 10; $round = $round + 1) {
    $start = hrtime(true);
    $result = benchmark();
    $elapsed = hrtime(true) - $start;
    echo 'round=', $round, ' ns=', $elapsed, ' result=', $result, "\n";
}
