<?php
var_dump(json_validate('{"a":1}'));
var_dump(json_validate('bad'));
var_dump(json_validate(''));
var_dump(json_validate('1'));
var_dump(json_last_error());

$invalid = "{\"a\":\"\xff\"}";
var_dump(json_validate($invalid));
var_dump(json_validate($invalid, 512, JSON_INVALID_UTF8_IGNORE));
var_dump(json_last_error());

try {
    json_validate('x', 0);
} catch (ValueError $e) {
    echo $e->getMessage(), "\n";
}

try {
    json_validate('x', 512, JSON_THROW_ON_ERROR);
} catch (ValueError $e) {
    echo $e->getMessage(), "\n";
}
