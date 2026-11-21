<?php
// Whole-file reads and stream handles: contents, windows, lines and directories.
$base = sys_get_temp_dir() . '/php-file-reads-' . getmypid();
if (is_dir($base)) {
    foreach (scandir($base) as $f) {
        if ($f === '.' || $f === '..') {
            continue;
        }
        $p = "$base/$f";
        if (is_dir($p) && !is_link($p)) {
            foreach (scandir($p) as $g) {
                if ($g !== '.' && $g !== '..') {
                    unlink("$p/$g");
                }
            }
            rmdir($p);
        } else {
            unlink($p);
        }
    }
    rmdir($base);
}
mkdir($base);
mkdir("$base/dir");
file_put_contents("$base/a.txt", "one\ntwo\r\n\nthree");
file_put_contents("$base/empty.txt", '');
file_put_contents("$base/bin.dat", "\x00\x01\xff");
file_put_contents("$base/dir/inner.txt", 'inner');
symlink('a.txt', "$base/link-file");
symlink('gone', "$base/link-missing");

$old = getcwd();
chdir($base);

echo 'fgc|', json_encode(file_get_contents('a.txt')), '|', json_encode(file_get_contents('link-file')), '|',
    json_encode(file_get_contents('empty.txt')), '|', json_encode(file_get_contents('bin.dat')), "\n";
echo 'window|', json_encode(file_get_contents('a.txt', false, null, 4)),
    json_encode(file_get_contents('a.txt', false, null, -5)),
    json_encode(file_get_contents('a.txt', false, null, 4, 5)),
    json_encode(file_get_contents('a.txt', false, null, 1000)), "\n";
echo 'readfail|', json_encode(@file_get_contents('missing')), json_encode(file_get_contents('link-missing')),
    json_encode(file_get_contents('dir')), "\n";

echo 'file|', json_encode(file('a.txt')), '|',
    json_encode(file('a.txt', FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES)), '|',
    json_encode(file('empty.txt')), '|', json_encode(file('dir')), '|', json_encode(@file('missing')), "\n";

ob_start();
$count = @readfile('a.txt');
$text = ob_get_clean();
echo 'readfile|', $count, '|', json_encode($text), "\n";
ob_start();
$count = @readfile('dir');
$text = ob_get_clean();
echo 'readfile dir|', $count, '|', json_encode($text), "\n";

echo 'hash|', md5_file('a.txt'), '|', sha1_file('a.txt'), '|', md5_file('dir'), '|',
    md5_file('empty.txt'), '|', json_encode(@md5_file('missing')), "\n";

$f = fopen('a.txt', 'rb');
echo 'read|', ftell($f), '|', json_encode(fread($f, 4)), '|', ftell($f), '|',
    json_encode(fgets($f)), '|', json_encode(fgetc($f)), '|', json_encode(feof($f)), '|',
    fseek($f, -5, SEEK_END), '|', json_encode(fread($f, 5)), '|', json_encode(fread($f, 1)), '|',
    json_encode(feof($f)), '|', fstat($f)['size'], '|', json_encode(fseek($f, 2)), '|',
    json_encode(fread($f, 2)), "\n";
fclose($f);

$f = fopen('a.txt', 'r');
echo 'short read|', json_encode(fread($f, 3)), '|', json_encode(fread($f, 1)), "\n";
fclose($f);

$f = @fopen('dir', 'r');
echo 'dir stream|', is_resource($f) ? 1 : 0;
if ($f) {
    echo '|', json_encode(@fread($f, 10));
    fclose($f);
}
echo "\n";
echo 'open dir w|', json_encode(@fopen('dir', 'w')), '|',
    json_encode(@fopen('missing', 'r')), '|', json_encode(@fopen('link-missing', 'r')), "\n";

$f = fopen('new.txt', 'x');
echo 'x mode|', is_resource($f) ? 1 : 0, '|', json_encode(@fopen('new.txt', 'x')), '|';
fwrite($f, 'abc');
fclose($f);
echo json_encode(file_get_contents('new.txt')), "\n";

$f = fopen('dir/app.txt', 'ab');
echo 'append|', fwrite($f, 'x'), '|', fwrite($f, 'yz'), '|', ftell($f);
fclose($f);
echo '|', json_encode(file_get_contents('dir/app.txt')), "\n";

$f = fopen('trunc.txt', 'w+');
fwrite($f, 'abcdef');
echo 'truncate|', json_encode(ftell($f)), '|';
fseek($f, 0);
echo json_encode(fread($f, 3)), '|', json_encode(ftruncate($f, 1)), '|';
fclose($f);
echo json_encode(file_get_contents('trunc.txt')), "\n";

$d = opendir('.');
$names = [];
while (($name = readdir($d)) !== false) {
    $names[] = $name;
}
sort($names);
echo 'opendir|', json_encode($names), '|';
rewinddir($d);
echo json_encode(readdir($d)), '|';
closedir($d);
echo json_encode(@opendir('missing')), "\n";

echo 'scandir|', json_encode(scandir('.')), '|', json_encode(scandir('.', SCANDIR_SORT_DESCENDING)), '|',
    json_encode(@scandir('missing')), "\n";
$none = scandir('.', SCANDIR_SORT_NONE);
sort($none);
echo 'scandir none|', json_encode($none), "\n";

chdir($old);

foreach (['a.txt', 'empty.txt', 'bin.dat', 'new.txt', 'trunc.txt', 'link-file', 'link-missing'] as $f) {
    unlink("$base/$f");
}
foreach (['inner.txt', 'app.txt'] as $f) {
    unlink("$base/dir/$f");
}
rmdir("$base/dir");
rmdir($base);
