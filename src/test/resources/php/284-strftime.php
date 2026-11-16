<?php
date_default_timezone_set("UTC");
$ts = mktime(14, 30, 45, 5, 6, 2024); // Monday 2024-05-06
$formats = [
    "%a", "%A", "%b", "%B", "%c", "%C", "%d", "%D", "%e", "%F", "%g", "%G",
    "%h", "%H", "%I", "%j", "%k", "%l", "%m", "%M", "%n", "%p", "%P", "%r",
    "%R", "%S", "%s", "%t", "%T", "%u", "%U", "%V", "%w", "%W", "%x", "%X",
    "%y", "%Y", "%z", "%Z", "%%", "%Q",
];
foreach ($formats as $f) {
    echo $f, "=", strftime($f, $ts), "\n";
}
echo strftime("mix %Y-%m-%d %H:%M:%S %j %U %W %V %G", $ts), "\n";
echo strftime("%-d|%_d|%0e|%^a|%Oy", $ts), "\n";
echo strftime("", $ts), "|\n";
echo strftime("%U %W %V %G %g", mktime(0, 0, 0, 1, 1, 2023)), "\n";
echo strftime("%U %W %V %G %g", mktime(0, 0, 0, 12, 31, 2023)), "\n";
date_default_timezone_set("Europe/Berlin");
echo strftime("%Z|%z|%H:%M|%c", $ts), "\n";
date_default_timezone_set("UTC");
echo strftime("%Y-%m-%d %H:%M:%S %Z %z", 0), "\n";
