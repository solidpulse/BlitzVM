<?php
define('PARENT_CONST', 'hello_parent');
define('INC_DIR', __DIR__ . '/inc');

$r1 = include INC_DIR . '/define_const.php';
var_dump($r1, FROM_INC, inc_hoisted());

// Return array, no return, empty return
$arr = include 'inc/return_array.php';
$none = include 'inc/no_return.php';
$nullRet = include 'inc/return_null.php';
var_dump($arr, $none, $nullRet);

// _once semantics (including canonicalisation via ./ and ../)
$GLOBALS['once_count'] = 0;
$o1 = require_once INC_DIR . '/once_counter.php';
$o2 = require_once 'inc/../inc/./once_counter.php';
$o3 = include_once 'inc/once_counter.php';
var_dump($o1, $o2, $o3, $GLOBALS['once_count']);

// include followed by include_once of same file
$a1 = include 'inc/return_array.php';
$a2 = include_once 'inc/return_array.php';
var_dump($a1['n'], $a2);

// Entry script counts as already included
var_dump(include_once __FILE__);
var_dump(require_once basename(__FILE__));

// Include inside expressions (with and without parens)
if ((include 'inc/no_return.php') === 1) {
    echo "expr_if_ok\n";
}
$sum = 10 + (include 'inc/nested/inner.php');
$keyVal = (include 'inc/return_array.php')['k'];
var_dump($sum, $keyVal);

// Nested relative path resolution (outer.php -> 'inner.php' in inc/nested/)
$nested = require 'inc/nested/outer.php';
var_dump($nested);

// CWD-relative fallback when not found relative to containing file
$cwdInc = include 'src/test/resources/php/inc/no_return.php';
var_dump($cwdInc);
