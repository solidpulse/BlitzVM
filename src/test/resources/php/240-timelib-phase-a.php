<?php
// Phase A conformance fixture: scanner rules (timestamp, iso8601date4, gnunocolon),
// date_parse, strtotime, and DateTimeZone.

date_default_timezone_set('UTC');

$p1 = date_parse('@1700000000');
echo "p1: ", $p1['year'], "-", $p1['month'], "-", $p1['day'], " rel=", $p1['relative']['second'], " err=", $p1['error_count'], "\n";

$p2 = date_parse('2024-01-02');
echo "p2: ", $p2['year'], "-", $p2['month'], "-", $p2['day'], " err=", $p2['error_count'], "\n";

$p3 = date_parse('1430');
echo "p3: ", $p3['hour'], ":", $p3['minute'], ":", $p3['second'], " err=", $p3['error_count'], "\n";

$p4 = date_parse('');
echo "p4: err=", $p4['error_count'], " ", $p4['errors'][0], "\n";

echo "st1: ", strtotime('@1700000000'), "\n";
echo "st2: ", strtotime('2024-01-02'), "\n";

$tz1 = new DateTimeZone('Europe/Berlin');
echo "tz1: ", $tz1->getName(), "\n";
$tz2 = new DateTimeZone('+05:30');
echo "tz2: ", $tz2->getName(), "\n";

$tz1Clone = clone $tz1;
echo "clone: ", $tz1Clone->getName(), "\n";
echo "json: ", json_encode($tz1), "\n";
$arr = (array) $tz2;
echo "cast: ", $arr['timezone_type'], " ", $arr['timezone'], "\n";
var_dump($tz1);

try {
    new DateTimeZone('Invalid/Zone_Name');
} catch (DateInvalidTimeZoneException $e) {
    echo "caught: ", get_class($e), "\n";
}
