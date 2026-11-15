<?php
class AutoStatic {
    public const VERSION = 42;
    public static function ping($s): string { return 'pong:' . $s; }
}
