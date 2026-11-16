<?php
class AutoSelfConsts {
    const VERSION = '2.0';
    const DEFAULTS = ['agent' => 'php-requests/' . self::VERSION, 'n' => self::N * 2];
    const N = 21;
    protected static $path = __DIR__ . '/../certificates/cacert.pem';
    public $copy = self::VERSION;
    public static function opts() { $d = static::DEFAULTS; $d['verify'] = basename(self::$path); return $d; }
}
class AutoSelfChild extends AutoSelfConsts {
    const DEFAULTS = parent::DEFAULTS + ['child' => self::VERSION];
}
