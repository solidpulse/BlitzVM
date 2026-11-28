<?php
// error_reporting() keeps a level: it is set and read back, agrees with ini_get/ini_set, and a
// user error handler can test it. is_a() with a string object raises E_DEPRECATED on the
// calling line, which the handler sees whatever the level.
echo (error_reporting() & E_DEPRECATED) === 0 ? "default masks deprecations\n" : "default reports deprecations\n";
echo (error_reporting() & E_WARNING) !== 0 ? "default reports warnings\n" : "default masks warnings\n";
echo "ini agrees: ", ini_get('error_reporting') === (string) error_reporting() ? "yes" : "no", "\n";
$seen = [];
set_error_handler(function (int $code, string $msg, string $file, int $line) use (&$seen) {
    $seen[] = [$code, $line, (error_reporting() & $code) !== 0];
    return true;
});
function check(): bool {
    return is_a('stdClass', 'stdClass');
}
var_dump(check());
$old = error_reporting(E_ALL);
echo "old masked deprecations: ", ($old & E_DEPRECATED) === 0 ? "yes" : "no", ", now E_ALL: ", error_reporting() === E_ALL ? "yes" : "no", "\n";
var_dump(is_a('stdClass', 'stdClass', false));
echo "ini_set returns E_ALL: ", ini_set('error_reporting', (string) E_WARNING) === (string) E_ALL ? "yes" : "no", ", ini_get ", ini_get('error_reporting'), "\n";
echo "error_reporting() ", error_reporting(), "\n";
error_reporting(null);
echo "after null ", error_reporting(), "\n";
var_dump(is_a(new stdClass, 'stdClass'), is_a('stdClass', 'stdClass', true));
foreach ($seen as [$code, $line, $reported]) {
    echo "handled $code on line $line, reported ", $reported ? "yes" : "no", "\n";
}
