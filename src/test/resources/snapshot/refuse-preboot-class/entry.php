<?php
class PreBootClass
{
    public static $value = 1;
}

require_once __DIR__ . '/boot.php';
echo "after\n";
