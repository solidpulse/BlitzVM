<?php
declare(strict_types=1);
namespace App;
var_dump(array_map('strtoupper', ['a', 'b']));
try { var_dump(array_map('trim', [1, 2.5, true])); } catch (\TypeError $e) { echo $e->getMessage(), "\n"; }
try { var_dump(call_user_func('strlen', 42)); } catch (\TypeError $e) { echo $e->getMessage(), "\n"; }
try { var_dump(strlen(42)); } catch (\TypeError $e) { echo $e->getMessage(), "\n"; }
function weakish() { return array_map(fn($x) => str_pad((string) $x, 3, '0', STR_PAD_LEFT), [1, 22]); }
var_dump(weakish());
