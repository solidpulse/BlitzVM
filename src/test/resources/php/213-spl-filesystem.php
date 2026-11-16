<?php
$dir = sys_get_temp_dir() . '/php-spl-task07-' . getmypid();
mkdir($dir);
file_put_contents($dir . '/z.txt', 'abc');
mkdir($dir . '/sub');
file_put_contents($dir . '/a.json', '{}');
file_put_contents($dir . '/sub/inner.txt', 'inside');
$info = new SplFileInfo($dir . '/z.txt');
echo $info->getFilename(), ':', $info->getExtension(), ':', $info->getBasename('.txt'), ':', $info->getSize(), ':', $info->getType(), "\n";
var_dump($info->isFile(), $info->isDir(), $info->isReadable());
$it = new DirectoryIterator($dir);
foreach ($it as $entry) { if (!$entry->isDot()) { echo $entry->getFilename(), '|'; } }
echo "\n";
$flags = FilesystemIterator::SKIP_DOTS | FilesystemIterator::KEY_AS_FILENAME | FilesystemIterator::CURRENT_AS_PATHNAME;
$it = new FilesystemIterator($dir, $flags);
foreach ($it as $key => $path) { echo $key, ':', basename($path), '|'; }
echo "\n";
$it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($dir, FilesystemIterator::SKIP_DOTS));
foreach ($it as $key => $entry) { echo $it->getDepth(), ':', $entry->getFilename(), ':', $entry->getSize(), '|'; }
echo "\n";
$r = new RecursiveDirectoryIterator($dir, FilesystemIterator::SKIP_DOTS);
foreach ($r as $entry) {
    if ($r->hasChildren()) {
        $child = $r->getChildren();
        foreach ($child as $file) { echo $child->getSubPathname(), ':', $file->getFilename(), '|'; }
    }
}
echo "\n";
unlink($dir . '/z.txt'); unlink($dir . '/a.json'); unlink($dir . '/sub/inner.txt');
rmdir($dir . '/sub'); rmdir($dir);
