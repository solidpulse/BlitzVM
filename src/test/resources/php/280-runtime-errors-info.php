<?php
// trigger_error and user_error route through set_error_handler with every E_USER_* level.
set_error_handler(function ($errno, $errstr, $errfile, $errline) {
    echo "handler:", $errno, ":", $errstr, ":", basename($errfile), ":", $errline, "\n";
    return true;
});
var_dump(trigger_error("plain notice"));
var_dump(trigger_error("warning text", E_USER_WARNING));
var_dump(user_error("notice text", E_USER_NOTICE));
var_dump(trigger_error("deprecated text", E_USER_DEPRECATED));
var_dump(trigger_error("fatal text", E_USER_ERROR));
restore_error_handler();
echo "after handler\n";

try {
    trigger_error("bad level", 1234);
} catch (Throwable $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}

// zend.assertions is -1, so assert() is compiled out: arguments are not evaluated.
$sideEffect = 0;
var_dump(assert($sideEffect = 1));
var_dump($sideEffect);
var_dump(assert(false, "ignored"));

// Runtime information.
var_dump(gc_enabled());
echo preg_match('/^\d+\.\d+\.\d+$/', zend_version()), "\n";
var_dump(ignore_user_abort());
var_dump(ignore_user_abort(true));
var_dump(ignore_user_abort());
var_dump(ignore_user_abort(0));
var_dump(ignore_user_abort());

echo php_uname("s"), "|", php_uname("m"), "\n";
var_dump(strlen(php_uname("n")) > 0);
var_dump(strlen(php_uname("r")) > 0);
var_dump(strlen(php_uname("v")) > 0);
var_dump(str_starts_with(php_uname(), php_uname("s") . " " . php_uname("n") . " "));
try {
    php_uname("q");
} catch (Throwable $e) {
    echo get_class($e), "\n";
}

// get_defined_functions lists internal names sorted and user names lowercase.
function ZedFunction() {
}
function anotherFunction() {
}
$defined = get_defined_functions();
var_dump(in_array("strlen", $defined["internal"], true));
var_dump(in_array("array_map", $defined["internal"], true));
var_dump(in_array("zedfunction", $defined["user"], true));
var_dump(in_array("anotherfunction", $defined["user"], true));
var_dump(in_array("get_defined_functions", $defined["internal"], true));
var_dump(count($defined["internal"]) > 100);
$withoutDisabled = get_defined_functions(false);
var_dump($withoutDisabled["user"] === $defined["user"]);

// phpinfo writes a short text form and returns true.
ob_start();
$infoReturn = phpinfo(INFO_GENERAL);
$infoText = ob_get_clean();
var_dump($infoReturn);
var_dump(str_contains($infoText, "phpinfo()"));
var_dump(str_contains($infoText, "PHP Version"));
var_dump(str_contains($infoText, php_uname("s")));
