<?php
// glob(): wildcards, bracket classes, braces, flags, trailing slashes and dangling links.
$base = sys_get_temp_dir() . '/php-glob-' . getmypid();
if (is_dir($base)) {
    foreach (scandir($base) as $f) {
        if ($f === '.' || $f === '..') {
            continue;
        }
        $p = "$base/$f";
        if (is_link($p) || is_file($p)) {
            unlink($p);
        } else {
            foreach (scandir($p) as $g) {
                if ($g !== '.' && $g !== '..') {
                    unlink("$p/$g");
                }
            }
            rmdir($p);
        }
    }
    rmdir($base);
}
mkdir($base);
mkdir("$base/alpha");
mkdir("$base/beta");
mkdir("$base/empty");
mkdir("$base/[br]");
file_put_contents("$base/a.txt", 'a');
file_put_contents("$base/b.txt", 'b');
file_put_contents("$base/alpha/one.json", '1');
file_put_contents("$base/beta/two.json", '2');
file_put_contents("$base/beta/three.txt", '3');
file_put_contents("$base/[br]/x", 'x');
file_put_contents("$base/.hidden", 'h');
file_put_contents("$base/*star", 's');
symlink('a.txt', "$base/link-file");
symlink('alpha', "$base/link-dir");
symlink('gone', "$base/link-missing");

$old = getcwd();
chdir($base);

function show($label, $pattern, $flags = 0) {
    $value = $flags === 0 ? glob($pattern) : glob($pattern, $flags);
    if ($value === false) {
        echo $label, '|false', "\n";
        return;
    }
    if ($flags & GLOB_NOSORT) {
        sort($value);
    }
    echo $label, '|', json_encode($value), "\n";
}

show('star', '*');
show('txt', '*.txt');
show('two levels', '*/*.json');
show('all levels', '*/*');
show('dirs only slash', '*/');
show('link', 'link*');
show('literal', 'a.txt');
show('literal dir', 'alpha');
show('literal missing', 'missing');
show('missing below', 'missing/*');
show('class', '[ab].txt');
show('class range', '[a-c]*');
show('class negated', '[!a]*');
show('question', '?.txt');
show('dot class', '?[.]txt');
show('dots', '.*');
show('dot class literal', '[.]hidden');
show('bracket dir', '[[]br]*');
show('unterminated bracket', '[');
show('no magic check', 'nope*');
show('nocheck', 'nope*', GLOB_NOCHECK);
show('brace', '{a,b}*', GLOB_BRACE);
show('brace suffix', '{a,b}.txt', GLOB_BRACE);
show('brace no flag', '{a,b}.txt');
show('brace nocheck', 'nope{x,y}', GLOB_BRACE | GLOB_NOCHECK);
show('escaped star', '\\*star');
show('escaped star noescape', '\\*star', GLOB_NOESCAPE);
show('mark', '*', GLOB_MARK);
show('mark dir slash', '*/', GLOB_MARK);
show('onlydir', '*', GLOB_ONLYDIR);
show('onlydir slash', '*/', GLOB_ONLYDIR);
show('onlydir mark', '*', GLOB_ONLYDIR | GLOB_MARK);
show('onlydir file pattern', 'a.txt', GLOB_ONLYDIR);
show('nosort', '*', GLOB_NOSORT);
show('two stars', '**');
show('two levels dots', '*/.hidden');
show('empty pattern', '', GLOB_NOCHECK);

chdir($old);

foreach (scandir($base) as $f) {
    if ($f === '.' || $f === '..') {
        continue;
    }
    $p = "$base/$f";
    if (is_link($p) || is_file($p)) {
        unlink($p);
    }
}
unlink("$base/[br]/x");
unlink("$base/alpha/one.json");
unlink("$base/beta/two.json");
unlink("$base/beta/three.txt");
rmdir("$base/[br]");
rmdir("$base/alpha");
rmdir("$base/beta");
rmdir("$base/empty");
rmdir($base);
