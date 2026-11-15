<?php
if (!function_exists('includedThrower')) {
    function includedThrower($msg) {
        throw new OverflowException("from-include:" . $msg, 88);
    }
}

if (isset($THROW_AT_TOP_OF_INCLUDE) && $THROW_AT_TOP_OF_INCLUDE) {
    throw new UnderflowException("top-of-include");
}
