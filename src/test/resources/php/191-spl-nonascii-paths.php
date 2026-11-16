<?php
// SPL file iterators and SplFileInfo with non-ASCII file names: names are bytes (UTF-8 on disk).
$d = sys_get_temp_dir() . '/blitz-spl-utf8-' . getmypid();
@mkdir($d);
touch("$d/café.txt");
touch("$d/日本.php");
@mkdir("$d/sübdir");
touch("$d/sübdir/ünï.md");

$names = [];
foreach (new DirectoryIterator($d) as $f) {
    if (!$f->isDot()) {
        $names[] = $f->getFilename() . '|' . strlen($f->getFilename()) . '|' . $f->getExtension() . '|' . ($f->isDir() ? 'dir' : 'file');
    }
}
sort($names);
echo implode("\n", $names), "\n";

$rel = [];
$it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($d, FilesystemIterator::SKIP_DOTS));
foreach ($it as $path => $info) {
    $rel[] = substr($path, strlen($d) + 1) . ':' . strlen($info->getBasename('.' . $info->getExtension()));
}
sort($rel);
echo implode("\n", $rel), "\n";

$i = new SplFileInfo("$d/café.txt");
echo $i->getFilename(), ' ', $i->isFile() ? 'file' : 'missing', ' ', bin2hex($i->getBasename('.txt')), "\n";
echo $i->getRealPath() === realpath("$d/café.txt") ? 'realpath ok' : 'realpath differs', "\n";

unlink("$d/sübdir/ünï.md");
rmdir("$d/sübdir");
unlink("$d/café.txt");
unlink("$d/日本.php");
rmdir($d);
