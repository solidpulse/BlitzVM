<?php
$dir = sys_get_temp_dir() . "/phprt-dir-" . getmypid();
@mkdir($dir);
file_put_contents($dir . "/b.txt", "b");
file_put_contents($dir . "/a.txt", "a");

$d = dir($dir);
var_dump(get_class($d), strlen($d->path) > 0, is_resource($d->handle), get_resource_type($d->handle));
$names = [];
while (($n = $d->read()) !== false) {
    $names[] = $n;
}
sort($names);
var_dump($names);
$d->rewind();
var_dump(in_array($d->read(), [".", "..", "a.txt", "b.txt"], true));
$d->close();
try {
    $d->read();
} catch (Throwable $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
var_dump(dir($dir . "/missing"));

// chdir relative file access
$old = getcwd();
var_dump(chdir($dir));
var_dump(file_get_contents("a.txt"));
var_dump(chdir($old));
var_dump(chdir($dir . "/missing"));
var_dump(strlen(getcwd()) > 0);

@unlink($dir . "/a.txt");
@unlink($dir . "/b.txt");
@rmdir($dir);

// mime_content_type and the file predicates
$mimeDir = sys_get_temp_dir() . "/phprt-mime-" . getmypid();
@mkdir($mimeDir);
file_put_contents($mimeDir . "/empty", "");
file_put_contents($mimeDir . "/t.txt", "hello world\n");
file_put_contents($mimeDir . "/c.php", "<?php echo 1;");
file_put_contents($mimeDir . "/d.json", '{"a":1}');
file_put_contents($mimeDir . "/p.svg", '<svg xmlns="http://www.w3.org/2000/svg"></svg>');
file_put_contents($mimeDir . "/d.pdf", "%PDF-1.4\n");
file_put_contents($mimeDir . "/j.jpg", "\xff\xd8\xff\xe0");
file_put_contents($mimeDir . "/g.gif", "GIF89a");
file_put_contents($mimeDir . "/a.gz", gzencode("hello"));
file_put_contents($mimeDir . "/s.sh", "#!/bin/bash\necho hi\n");
chmod($mimeDir . "/s.sh", 493);
file_put_contents($mimeDir . "/bin.dat", "\x00\x01\x02\xff");
foreach (["empty", "t.txt", "c.php", "d.json", "p.svg", "d.pdf", "j.jpg", "g.gif", "a.gz", "s.sh", "bin.dat", "missing"] as $f) {
    echo $f, " => ", var_export(mime_content_type($mimeDir . "/" . $f), true), "\n";
}
var_dump(is_executable($mimeDir . "/s.sh"), is_executable($mimeDir . "/t.txt"), is_executable($mimeDir . "/missing"));
var_dump(is_uploaded_file($mimeDir . "/t.txt"), move_uploaded_file($mimeDir . "/t.txt", $mimeDir . "/moved.txt"));
foreach (glob($mimeDir . "/*") as $f) {
    @unlink($f);
}
@rmdir($mimeDir);
