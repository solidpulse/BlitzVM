<?php
// _wp_array_get is copied unchanged from WordPress wp-includes/functions.php.
function _wp_array_get( $input_array, $path, $default_value = null ) {
	// Confirm $path is valid.
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
			/*
			 * Check if the path element exists in the input array.
			 * We check with `isset()` first, as it is a lot faster
			 * than `array_key_exists()`.
			 */
			if ( isset( $path_element, $input_array[ $path_element ] ) ) {
				$input_array = $input_array[ $path_element ];
				continue;
			}

			/*
			 * If `isset()` returns false, we check with `array_key_exists()`,
			 * which also checks for `null` values.
			 */
			if ( isset( $path_element ) && array_key_exists( $path_element, $input_array ) ) {
				$input_array = $input_array[ $path_element ];
				continue;
			}
		}

		return $default_value;
	}

	return $input_array;
}
function wp_array_get_round() {
    $input = ['settings' => ['present' => 1]];
    $sum = 0;
    for ($i = 0; $i < 600000; $i++) {
        $sum += _wp_array_get($input, ['settings', 'missing'], 1);
    }
    return $sum;
}
function run_wp_array_get() {
    for ($round = 1; $round <= 15; $round++) {
        $t = microtime(true);
        $sum = wp_array_get_round();
        echo $round, ' wp_array_get ', (microtime(true) - $t) * 1000, ' ', $sum, "\n";
    }
}
run_wp_array_get();
