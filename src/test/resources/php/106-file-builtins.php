<?php
foreach (["/www/htdocs/inc/lib.inc.php", "file.txt", "noext", ".htaccess", "/a/b/", "", "/", "a.b.c", "dir/.hidden.tar.gz", "trail."] as $p) {
    echo json_encode($p), " ", json_encode(pathinfo($p)), " ", json_encode(pathinfo($p, PATHINFO_EXTENSION)), " ",
        json_encode(pathinfo($p, PATHINFO_DIRNAME)), " ", json_encode(pathinfo($p, PATHINFO_FILENAME)), " ",
        json_encode(pathinfo($p, PATHINFO_BASENAME)), "\n";
}

$dir = sys_get_temp_dir() . "/blitzvm-fixture-106";
// leftovers from an interrupted run
if (is_dir($dir)) {
    foreach (scandir($dir . "/sub") ?: [] as $f) {
        if ($f !== "." && $f !== "..") {
            unlink("$dir/sub/$f");
        }
    }
    if (is_dir("$dir/sub")) {
        rmdir("$dir/sub");
    }
    foreach (scandir($dir) as $f) {
        if ($f !== "." && $f !== ".." && !is_dir("$dir/$f")) {
            unlink("$dir/$f");
        }
    }
    foreach (["$dir/x/y/z", "$dir/x/y", "$dir/x"] as $d) {
        if (is_dir($d)) {
            rmdir($d);
        }
    }
    rmdir($dir);
}

var_dump(is_dir($dir), mkdir($dir), is_dir($dir), is_writable($dir), is_writeable($dir));
var_dump(file_put_contents("$dir/a.txt", "héllo\n"), file_put_contents("$dir/a.txt", "more\n", FILE_APPEND), file_get_contents("$dir/a.txt"));
var_dump(filesize("$dir/a.txt"), filemtime("$dir/a.txt") > 1700000000, abs(filemtime("$dir/a.txt") - time()) < 60);
var_dump(file_put_contents("$dir/b.txt", ["x", "y", 3]), file_get_contents("$dir/b.txt"), file_get_contents("$dir/a.txt", false, null, 1, 4));
var_dump(file_put_contents("$dir/c.txt", ""), file_get_contents("$dir/c.txt"), filesize("$dir/c.txt"));
var_dump(file_put_contents("$dir/Z-upper.txt", "z"), file_put_contents("$dir/_under.txt", "u"));
var_dump(scandir($dir), scandir($dir, SCANDIR_SORT_DESCENDING));
var_dump(mkdir("$dir/x/y/z", 0777, true), is_dir("$dir/x/y/z"));
var_dump(rmdir("$dir/x/y/z"), rmdir("$dir/x/y"), rmdir("$dir/x"));
foreach (["a.txt", "b.txt", "c.txt", "Z-upper.txt", "_under.txt"] as $f) {
    var_dump(unlink("$dir/$f"));
}
var_dump(file_exists("$dir/a.txt"), rmdir($dir), is_dir($dir));

var_dump(is_string(getenv("PATH")), getenv("BLITZVM_SURELY_UNSET_VARIABLE"), is_array(getenv()), getenv()["PATH"] === getenv("PATH"));
var_dump(ini_get("blitzvm.no_such_setting"), is_string(ini_get("memory_limit")), ini_get("serialize_precision"), ini_get("precision"));
var_dump(sys_get_temp_dir() === (getenv("TMPDIR") ? rtrim(getenv("TMPDIR"), "/") : "/tmp"));
var_dump(extension_loaded("json"), extension_loaded("JSON"), extension_loaded("hash"), extension_loaded("ctype"), extension_loaded("mbstring"),
    extension_loaded("date"), extension_loaded("standard"), extension_loaded("Core"), extension_loaded("blitzvm_nope"));
var_dump(in_array("standard", get_loaded_extensions()), in_array("date", get_loaded_extensions()), is_array(get_loaded_extensions(true)));
