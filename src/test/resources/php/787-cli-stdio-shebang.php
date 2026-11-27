#!/usr/bin/env php
<?php
// CLI SAPI: STDIN/STDOUT/STDERR are predefined streams with resource ids 1-3, and a
// leading "#!" line is skipped in the main script and in included files.
echo __LINE__, "\n";
foreach ([STDIN, STDOUT, STDERR] as $s) {
    $m = stream_get_meta_data($s);
    var_dump((int) $s, get_resource_type($s), $m["uri"], $m["wrapper_type"], $m["stream_type"]);
}
var_dump(defined("STDIN"), is_resource(STDERR));
$f = fopen("php://memory", "w+");
var_dump((int) $f);
include __DIR__ . "/inc/787-shebang.php";
