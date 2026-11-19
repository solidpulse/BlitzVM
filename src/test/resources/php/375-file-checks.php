<?php
// Existence checks on the host file system: missing paths must be answered, not thrown.
$base = sys_get_temp_dir() . '/php-file-checks-' . getmypid();
mkdir($base);
mkdir("$base/dir");
file_put_contents("$base/file", 'x');
symlink("$base/file", "$base/link-file");
symlink("$base/dir", "$base/link-dir");
symlink("$base/missing", "$base/link-missing");

function show($value, $base) {
    return is_string($value) ? str_replace($base, '<base>', $value) : $value;
}

function check5($label, $path, $base) {
    echo $label, "\n";
    var_dump(file_exists($path));
    var_dump(is_file($path));
    var_dump(is_dir($path));
    var_dump(is_readable($path));
    var_dump(show(realpath($path), $base));
}

check5('existing file', "$base/file", $base);
check5('existing directory', "$base/dir", $base);
check5('missing', "$base/missing", $base);
check5('symlink to file', "$base/link-file", $base);
check5('symlink to directory', "$base/link-dir", $base);
check5('symlink to missing target', "$base/link-missing", $base);
var_dump(is_link("$base/link-missing"));

check5('file with trailing slash', "$base/file/", $base);
check5('directory with trailing slash', "$base/dir/", $base);

echo "empty path\n";
var_dump(file_exists(''), is_file(''), is_dir(''), is_readable(''));
var_dump(realpath('') === getcwd());

chdir($base);
check5('relative existing file', 'file', $base);
check5('relative existing directory', 'dir', $base);
check5('relative missing', 'missing', $base);
check5('relative through dot dot', 'dir/../file', $base);
check5('relative with missing parent before dot dot', 'missing/../file', $base);

chdir('dir');
check5('relative after relative chdir', '../file', $base);
var_dump(is_dir('.'), is_file('../file'));
chdir('..');
var_dump(is_dir('file'), is_file('file'));

foreach (['file_exists', 'is_file', 'is_dir', 'is_readable', 'is_writable', 'is_executable'] as $fn) {
    var_dump($fn("a\0b"));
}
try {
    realpath("a\0b");
} catch (ValueError $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

unlink("$base/link-file");
unlink("$base/link-dir");
unlink("$base/link-missing");
unlink("$base/file");
rmdir("$base/dir");
rmdir($base);
