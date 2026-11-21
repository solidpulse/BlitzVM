<?php
// File predicates, symlinks, realpath and the stat family on one host directory.
$base = sys_get_temp_dir() . '/php-file-predicates-' . getmypid();
if (is_dir($base)) {
    foreach (scandir($base) as $f) {
        if ($f !== '.' && $f !== '..') {
            is_dir("$base/$f") && !is_link("$base/$f") ? rmdir("$base/$f") : unlink("$base/$f");
        }
    }
    rmdir($base);
}
mkdir($base);
mkdir("$base/dir");
file_put_contents("$base/file.txt", "hello\nworld");
file_put_contents("$base/dir/inner.txt", "inner");
symlink("file.txt", "$base/link-file");
symlink("dir", "$base/link-dir");
symlink("missing", "$base/link-missing");

function rel($value, $base) {
    return is_string($value) ? str_replace($base, '<base>', $value) : $value;
}

foreach (["$base/file.txt", "$base/dir", "$base/link-file", "$base/link-dir", "$base/link-missing",
          "$base/missing", "$base/file.txt/", "$base/dir/"] as $p) {
    echo rel($p, $base), '|',
        (int) file_exists($p), (int) is_file($p), (int) is_dir($p), (int) is_readable($p),
        (int) is_writable($p), (int) is_executable($p), (int) is_link($p), '|',
        json_encode(rel(realpath($p), $base)), "\n";
}

// Empty path and the working directory.
echo '<empty>|', (int) file_exists(''), (int) is_file(''), (int) is_dir(''),
    (int) is_readable(''), (int) is_link(''), '|', json_encode(realpath('') === getcwd()), "\n";

// Relative paths resolve against chdir, including the parent before "..".
$old = getcwd();
chdir($base);
echo 'relative|', (int) file_exists('file.txt'), (int) is_file('file.txt'), (int) is_dir('dir'),
    (int) is_link('link-file'), '|', json_encode(realpath('link-file') === "$base/file.txt"), "\n";
echo 'dotdot|', (int) file_exists('dir/../file.txt'), (int) file_exists('missing/../file.txt'),
    '|', json_encode(realpath('dir/../file.txt') === "$base/file.txt"),
    json_encode(realpath('missing/../file.txt')), "\n";
chdir('dir');
echo 'nested|', (int) file_exists('../file.txt'), (int) is_dir('.'), json_encode(basename(getcwd())), '|',
    json_encode(realpath('../file.txt') === "$base/file.txt"), "\n";
chdir($old);

// filesize and filemtime on files, directories and missing paths.
echo 'size|', filesize("$base/file.txt"), '|', filesize("$base/dir") > 0 ? 1 : 0, '|',
    json_encode(@filesize("$base/missing")), json_encode(@filesize("$base/file.txt/")), "\n";
echo 'mtime|', abs(filemtime("$base/file.txt") - time()) < 3600 ? 1 : 0, '|',
    abs(filemtime("$base/dir") - time()) < 3600 ? 1 : 0, '|',
    json_encode(@filemtime("$base/missing")), "\n";

// stat follows links, lstat does not; a trailing slash forces a directory. Directory sizes and
// link counts are filesystem-specific, so they are printed as a marker instead of a number.
foreach (["$base/file.txt", "$base/dir", "$base/link-file", "$base/link-dir", "$base/link-missing"] as $p) {
    $isDir = is_dir($p);
    $s = @stat($p);
    $l = @lstat($p);
    echo rel($p, $base), '|',
        $s === false ? 'false'
            : decoct($s['mode'] & 0777) . ':' . ($isDir ? 'dir' : $s['size']) . ':' . ($isDir ? 'dir' : $s['nlink']),
        '|',
        $l === false ? 'false'
            : decoct($l['mode'] & 0170000) . ':' . ($isDir && !is_link($p) ? 'dir' : $l['size']),
        "\n";
}
echo 'trailing|', json_encode(@stat("$base/file.txt/") !== false),
    json_encode(@lstat("$base/file.txt/") !== false);
$linkDir = @lstat("$base/link-dir/");
echo '|', $linkDir === false ? 'false' : decoct($linkDir['mode'] & 0170000), "\n";
echo 'missing|', json_encode(@stat("$base/missing")), json_encode(@lstat("$base/missing")), "\n";

echo 'fields|', decoct(fileperms("$base/file.txt") & 0777), '|',
    (int) (fileinode("$base/file.txt") > 0), '|', (int) is_int(fileowner("$base/file.txt")), '|',
    (int) is_int(filegroup("$base/file.txt")), '|',
    (int) (fileatime("$base/file.txt") > 0), '|', (int) (filectime("$base/file.txt") > 0), "\n";

// Fresh metadata after a write, and realpath forgets what clearstatcache(true) clears.
file_put_contents("$base/file.txt", "hello\nworld plus");
echo 'fresh|', filesize("$base/file.txt"), "\n";
clearstatcache(true);
unlink("$base/link-file");
echo 'cleared|', json_encode(realpath("$base/link-file")), "\n";

unlink("$base/link-dir");
unlink("$base/link-missing");
unlink("$base/file.txt");
unlink("$base/dir/inner.txt");
rmdir("$base/dir");
rmdir($base);
