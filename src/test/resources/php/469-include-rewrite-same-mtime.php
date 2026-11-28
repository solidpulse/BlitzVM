<?php
set_error_handler(function ($n, $s) { echo "warning:$s\n"; return true; });

// Symfony's PhpFilesAdapter rewrites cache files and backdates each to the same fixed time.
// A rewritten file must be read again even when its mtime did not change.
$dir = sys_get_temp_dir() . '/blitz-469-' . getmypid();
@mkdir($dir);
$file = "$dir/item.php";
$stamp = 1700000000;

function write_item(string $file, string $value, int $stamp, bool $rename): void {
    $code = "<?php return " . var_export($value, true) . ";\n";
    if ($rename) {
        $tmp = $file . '.tmp';
        file_put_contents($tmp, $code);
        touch($tmp, $stamp);
        rename($tmp, $file);
    } else {
        file_put_contents($file, $code);
        touch($file, $stamp);
    }
    clearstatcache();
    if (function_exists('opcache_invalidate')) {
        opcache_invalidate($file, true);
    }
}

write_item($file, 'first', $stamp, true);
var_dump(include $file, filemtime($file));
write_item($file, 'second', $stamp, true);       // same size, same mtime, new inode
var_dump(include $file, filemtime($file));
write_item($file, 'third!', $stamp, false);      // same size, same mtime, in place
var_dump(include $file, filemtime($file));
write_item($file, 'a much longer value', $stamp, false);
var_dump(include $file);

var_dump(function_exists('opcache_invalidate'), function_exists('opcache_compile_file'),
    function_exists('opcache_is_script_cached'));
unlink($file);
rmdir($dir);
