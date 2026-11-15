<?php
var_dump(define('MY_INT', 42));
var_dump(define('MY_STR', 'hello'));
var_dump(define('MY_BOOL', true));
var_dump(define('MY_NULL', null));
var_dump(define('MY_ARR', [1, 2, 3]));

var_dump(defined('MY_INT'), defined('my_int'), defined('MY_NULL'), defined('MISSING_CONST'));
var_dump(define('my_int', 99));
var_dump(MY_INT, my_int, MY_STR, MY_BOOL, MY_NULL, MY_ARR);
var_dump(constant('MY_INT'), constant('my_int'), constant('MY_NULL'));

// Compile-time constants via defined() and constant()
var_dump(
    defined('PHP_EOL'),
    defined('php_eol'),
    defined('DIRECTORY_SEPARATOR'),
    defined('PATH_SEPARATOR'),
    defined('PHP_OS'),
    defined('PHP_OS_FAMILY'),
    defined('PHP_VERSION_ID'),
    defined('PHP_MAJOR_VERSION'),
    defined('PHP_MINOR_VERSION'),
    defined('true'),
    defined('TRUE')
);
var_dump(
    constant('DIRECTORY_SEPARATOR'),
    constant('PATH_SEPARATOR'),
    constant('PHP_OS'),
    constant('PHP_OS_FAMILY'),
    is_int(constant('PHP_VERSION_ID')) && constant('PHP_VERSION_ID') >= 80300,
    constant('PHP_MAJOR_VERSION'),
    is_int(constant('PHP_MINOR_VERSION')) && constant('PHP_MINOR_VERSION') >= 3,
    constant('true'),
    constant('FALSE'),
    constant('null')
);

// E_* constants
var_dump(
    E_ERROR, E_WARNING, E_PARSE, E_NOTICE,
    E_CORE_ERROR, E_CORE_WARNING, E_COMPILE_ERROR, E_COMPILE_WARNING,
    E_USER_ERROR, E_USER_WARNING, E_USER_NOTICE, E_STRICT,
    E_RECOVERABLE_ERROR, E_DEPRECATED, E_USER_DEPRECATED,
    (E_ALL & (E_ERROR | E_WARNING | E_RECOVERABLE_ERROR | E_USER_DEPRECATED)) !== 0
);
var_dump(defined('E_STRICT'), constant('E_RECOVERABLE_ERROR'));

// Top-level const (including reading from hoisted function declared above)
function read_top_const() {
    return TOP_A + TOP_B;
}

const TOP_A = 10;
const TOP_B = 20, TOP_C = TOP_A * 3;
const TOP_ARR = ['x' => 1, 'y' => 2];

var_dump(TOP_A, TOP_B, TOP_C, TOP_ARR, read_top_const());
var_dump(defined('TOP_A'), constant('TOP_C'));
