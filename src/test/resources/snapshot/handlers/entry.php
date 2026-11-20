<?php
require_once __DIR__ . '/boot.php';

echo "timezone=", date_default_timezone_get(), "\n";
echo "mb=", mb_internal_encoding(), " ", mb_substitute_character(), "\n";
echo "lazy=", LazyClass::VALUE, "\n";
trigger_error('handled warning', E_USER_WARNING);
throw new RuntimeException('boom');
