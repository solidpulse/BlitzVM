<?php
require __DIR__ . '/inc_connect.php';
$s = blitz_db_settings();
mysqli_report(MYSQLI_REPORT_OFF);

$l = mysqli_init();
var_dump(mysqli_real_connect($l, $s[0], $s[1], 'wrong-password', null, $s[4]));
var_dump(mysqli_connect_errno(), $l->connect_errno);

$l = mysqli_init();
var_dump(mysqli_real_connect($l, $s[0], $s[1], $s[2], 'no_such_db_here', $s[4]));
var_dump(mysqli_connect_errno(), mysqli_connect_error(), $l->connect_errno);

$l = mysqli_init();
var_dump(mysqli_real_connect($l, '127.0.0.1', $s[1], $s[2], null, 1));
var_dump(mysqli_connect_errno(), mysqli_connect_error());

$db = blitz_connect();
var_dump(mysqli_connect_errno(), mysqli_connect_error(), $db->connect_errno);
var_dump(mysqli_query($db, "SELECT * FROM no_such_table"));
var_dump(mysqli_errno($db), mysqli_error($db), mysqli_sqlstate($db), mysqli_affected_rows($db));
var_dump($db->errno, $db->error, count($db->error_list));
var_dump(mysqli_query($db, "SELEC 1"));
var_dump(mysqli_errno($db));
var_dump(mysqli_query($db, "DO 1"), mysqli_errno($db), mysqli_error($db));
var_dump(mysqli_select_db($db, 'nope_db'), mysqli_errno($db));
var_dump(mysqli_select_db($db, $s[3]), mysqli_errno($db));
var_dump(mysqli_ping($db));
var_dump(mysqli_more_results($db), mysqli_next_result($db));
var_dump(mysqli_close($db));
