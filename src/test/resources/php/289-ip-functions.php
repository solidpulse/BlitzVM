<?php
$ips = [
    "127.0.0.1", "255.255.255.255", "0.0.0.0", "192.168.1.1", "1.2.3",
    "1.2.3.4.5", "256.1.1.1", "01.2.3.4", " 1.2.3.4", "1.2.3.4 ", "::1", "abc", "",
];
foreach ($ips as $ip) {
    echo json_encode($ip), " => ", var_export(ip2long($ip), true), "\n";
}
$longs = [0, 1, -1, 2130706433, 4294967295, 4294967296, -2130706433, true, null, "2130706433"];
foreach ($longs as $l) {
    echo json_encode($l), " => ", var_export(long2ip($l), true), "\n";
}
$hosts = gethostbynamel("localhost");
var_dump(is_array($hosts));
var_dump(in_array("127.0.0.1", $hosts, true));
$literal = gethostbynamel("127.0.0.1");
var_dump(is_array($literal), in_array("127.0.0.1", $literal, true));
