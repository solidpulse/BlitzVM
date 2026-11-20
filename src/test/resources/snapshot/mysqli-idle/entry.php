<?php
require_once __DIR__ . '/boot.php';

// None of these need the server: a restored link that is never used must stay unconnected.
echo 'server_info=', (mysqli_get_server_info($link) !== '' ? 'present' : 'empty'), "\n";
echo 'server_version=', (mysqli_get_server_version($link) > 0 ? 'present' : 'empty'), "\n";
echo 'host_info=', (mysqli_get_host_info($link) !== null ? 'present' : 'empty'), "\n";
echo 'charset=', mysqli_character_set_name($link), "\n";
echo 'escape=', mysqli_real_escape_string($link, "a'b"), "\n";
echo 'errno=', mysqli_errno($link), "\n";
