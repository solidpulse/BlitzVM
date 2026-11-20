<?php
// An implicit nullable parameter: php 8.4 deprecates `int $x = null`.
function implicitNullable(int $number = null) {
    var_export($number);
    echo "\n";
}
