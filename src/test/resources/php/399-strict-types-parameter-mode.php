<?php
declare(strict_types=1);

// In strict mode the calling file's directive applies: no scalar coercion except int to float.
function strip($s) {
    return str_replace(__DIR__ . '/', '', $s);
}

set_error_handler(function (int $errno, string $message) {
    echo 'DEPRECATED: ', $message, "\n";
    return true;
});

function strictInt(int $x) { echo 'int ', var_export($x, true), "\n"; }
function strictFloat(float $x) { echo 'float ', var_export($x, true), "\n"; }
function strictString(string $x) { echo 'string ', var_export($x, true), "\n"; }
function strictReturnInt(): int { return "7"; }

strictInt(5);
strictFloat(5);
try { strictInt("5"); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
try { strictString(5); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
try { strictFloat("5.5"); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
try { strictReturnInt(); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }

try { call_user_func('strictInt', "5"); } catch (TypeError $e) { echo 'cuf ', strip($e->getMessage()), "\n"; }
try { call_user_func_array('strictInt', ["5"]); } catch (TypeError $e) { echo 'cufa ', strip($e->getMessage()), "\n"; }
// Internal callback invocations use weak mode, unlike call_user_func.
var_dump(array_map('strictInt', ["5"]));
$strictClosure = function (int $x) { echo 'invoke ', var_export($x, true), "\n"; };
$strictClosure->__invoke("6");
