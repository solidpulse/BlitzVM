<?php
// Classes loaded by an autoloader (an include inside a function) whose constant and property
// initializers use self:: and parent::. These resolve to the declared class, not the
// including function's scope (the WordPress Requests library loads this way).
spl_autoload_register(function ($class) {
    if ($class === 'AutoSelfConsts' || $class === 'AutoSelfChild') {
        require_once __DIR__ . '/inc/autoload-self-consts.php';
    }
});
var_dump(AutoSelfConsts::opts());
var_dump(AutoSelfChild::DEFAULTS, (new AutoSelfChild)->copy);
function loadInsideFunction() {
    $local = 'visible';
    require_once __DIR__ . '/inc/autoload-self-consts.php';
    return $local;
}
echo loadInsideFunction(), "\n";
try { require ''; } catch (ValueError $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
