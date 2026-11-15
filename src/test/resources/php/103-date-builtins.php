<?php
var_dump(date_default_timezone_get());
$all = "d D j l N S w z W F m M n t L o Y y a A B g G h H i s u v e I O P p T Z c r U";
$stamps = [0, 951782400, 1700000000, 1690000000, 1735689599, 1609459200, -86400, 1104537600, 1230768000];
foreach ($stamps as $ts) {
    echo $ts, ": ", date($all, $ts), "\n";
    echo $ts, ": ", gmdate($all, $ts), "\n";
}
echo date("\\T\\o\\d\\a\\y \\i\\s l, jS \\of F Y", 1700000000), "\n";
foreach ([1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 24, 31] as $d) {
    echo date("jS ", mktime(0, 0, 0, 1, $d, 2024));
}
echo "\n";

foreach (["America/New_York", "Europe/Berlin", "Asia/Kolkata", "Asia/Dubai", "Australia/Sydney", "America/Sao_Paulo", "Europe/London", "Asia/Kathmandu"] as $tz) {
    var_dump(date_default_timezone_set($tz));
    echo $tz, ": ", date($all, 1700000000), " | ", date("T I", 1690000000), " | ", date_default_timezone_get(), "\n";
    var_dump(mktime(12, 30, 0, 6, 15, 2024), mktime(0, 0, 0, 3, 10, 2024));
}
date_default_timezone_set("UTC");

var_dump(mktime(0, 0, 0, 1, 1, 2000), mktime(0, 0, 0, 13, 1, 2023), mktime(0, 0, 0, 1, 0, 2024), mktime(25, 61, 61, 2, 29, 2023));
var_dump(mktime(0, 0, 0, 1, 1, 69), mktime(0, 0, 0, 1, 1, 70), mktime(0, 0, 0, 1, 1, 100), mktime(0, 0, 0, 1, 1, 1969));
var_dump(gmmktime(0, 0, 0, 1, 1, 1970), gmmktime(12, 0, 0, 7, 4, 2026), date("Y-m-d", mktime(0, 0, 0, 2, 30, 2024)));
var_dump(checkdate(2, 29, 2024), checkdate(2, 29, 2023), checkdate(13, 1, 2024), checkdate(12, 31, 32767), checkdate(1, 1, 0), checkdate(4, 31, 2024));
var_dump(strlen(date("Y-m-d H:i:s")) === 19, abs(mktime(date("H"), date("i"), date("s")) - time()) < 5);
