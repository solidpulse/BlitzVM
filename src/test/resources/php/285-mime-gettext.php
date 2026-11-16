<?php
$headers = [
    "=?UTF-8?B?SGVsbG8gV29ybGQ=?=",
    "=?UTF-8?Q?Hello_World?=",
    "=?UTF-8?Q?caf=C3=A9?=",
    "=?ISO-8859-1?Q?caf=E9?=",
    "=?UTF-8?B?SGVsbG8=?= =?UTF-8?B?V29ybGQ=?=",
    "=?UTF-8?B?SGVsbG8=?= tail",
    "prefix =?UTF-8?Q?x?= suffix",
    "=?UTF-8?X?abc?=",
    "=?BOGUS?Q?abc?=",
    "=?UTF-8?Q?bad=ZZ?=",
    "=?UTF-8?B?!!!?=",
    "=?UTF-8?Q?under_score?=",
    "=?UTF-8?Q??=",
    "=?UTF-8?B??=",
    "a\nb",
    "=?UTF-8*en?Q?hi?=",
];
foreach ($headers as $h) {
    echo json_encode($h), " => ", json_encode(mb_decode_mimeheader($h)), "\n";
}
echo json_encode(mb_decode_mimeheader("=?UTF-8?Q?a=3Db?=")), "\n";

var_dump(_("Hello"));
var_dump(gettext("World"));
var_dump(function_exists("_"), function_exists("gettext"));
