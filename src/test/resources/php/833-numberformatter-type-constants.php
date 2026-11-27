<?php
// NumberFormatter's parse-style constants, used by Illuminate\Support\Number defaults.
foreach (['TYPE_DEFAULT', 'TYPE_INT32', 'TYPE_INT64', 'TYPE_DOUBLE', 'TYPE_CURRENCY'] as $name) {
    echo $name, '=', constant('NumberFormatter::' . $name), "\n";
}
$r = new ReflectionClass('NumberFormatter');
var_dump($r->hasConstant('TYPE_DOUBLE'));
