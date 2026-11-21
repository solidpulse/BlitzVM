<?php
// Literal keys written in one included file and read in another (and the reverse), plus a
// constant literal array built in one file and read with literal keys in the other.

require __DIR__ . '/inc/array-intern-keys.php';

$a = [];
intern_store($a);
echo intern_read($a), "\n";
echo intern_read_one($a, "shared-literal"), "\n";
echo $a["shared-literal"], $a["another-literal"], "\n";
echo $a["5"], $a["05"], "\n";
var_dump(array_key_exists("5", $a), array_key_exists(5, $a), array_key_exists("05", $a));

// a literal written here and read by the included function
$b = ["shared-literal" => 10, "another-literal" => 20];
echo intern_read($b), "\n";
$b["another-literal"] = 5;
echo intern_read($b), "\n";

// the included file's constant array, read with keys written here
echo $INTERN_LITERAL["shared-literal"], $INTERN_LITERAL["another-literal"], "\n";
echo $INTERN_LITERAL["another-literal"] + $INTERN_LITERAL["shared-literal"], "\n";

// the same value built at run time is not interned, but must still compare equal
$dynamic = "shared" . "-literal";
$c = [];
$c[$dynamic] = 3;
echo $c["shared-literal"], "\n";
echo $a[$dynamic], "\n";
