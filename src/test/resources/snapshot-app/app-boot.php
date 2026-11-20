<?php
define('APP_BOOT', 'booted');
define('APP_REQUEST_TIME', microtime(true));

$app_counter = 7;
$GLOBALS['boot_marker'] = 'boot';

class AppService
{
    public $name;

    public function __construct($name)
    {
        $this->name = $name;
    }
}

class AppState
{
    public static $count = 5;

    public static function describe()
    {
        return 'count=' . self::$count;
    }
}

$app_service = new AppService('svc');

register_shutdown_function(function () {
    echo 'shutdown=' . $GLOBALS['boot_marker'] . "\n";
});
