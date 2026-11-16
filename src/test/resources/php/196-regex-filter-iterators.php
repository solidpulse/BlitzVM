<?php
$a = new ArrayIterator(['x' => 'apple.html', 'y' => 'pear.txt', 'z' => 'b.HTML', 'n' => 'none', 'arr' => ['q']]);
foreach ([RegexIterator::MATCH, RegexIterator::GET_MATCH, RegexIterator::ALL_MATCHES, RegexIterator::SPLIT, RegexIterator::REPLACE] as $mode) {
    $r = new RegexIterator($a, '/^(\w+)\.(html)$/i', $mode);
    if ($mode === RegexIterator::REPLACE) { $r->replacement = '[$1]'; }
    echo "mode $mode: ", json_encode(iterator_to_array($r)), "\n";
}
echo json_encode(iterator_to_array(new RegexIterator($a, '/^[xz]$/', RegexIterator::MATCH, RegexIterator::USE_KEY))), "\n";
echo json_encode(iterator_to_array(new RegexIterator($a, '/html/i', RegexIterator::MATCH, RegexIterator::INVERT_MATCH))), "\n";
echo json_encode(iterator_to_array(new RegexIterator($a, '/(a)/', RegexIterator::GET_MATCH, 0, PREG_OFFSET_CAPTURE))), "\n";
$r = new RegexIterator($a, '/x/', RegexIterator::SPLIT); echo $r->getMode(), $r->getFlags(), $r->getPregFlags(), $r->getRegex(), "\n";
class Even extends FilterIterator { public function accept(): bool { return $this->current() % 2 === 0; } }
echo json_encode(iterator_to_array(new Even(new ArrayIterator([1, 2, 3, 4, 6])))), "\n";
var_dump(RecursiveRegexIterator::GET_MATCH, (new RegexIterator($a, '/a/')) instanceof FilterIterator);
// WordPress's _get_block_templates_paths(): .html files under a theme's templates directory.
$d = sys_get_temp_dir() . '/blitz-regexit-' . getmypid();
@mkdir("$d/parts", 0777, true);
foreach (['index.html', 'single.html', 'parts/header.html', 'notes.txt'] as $f) { file_put_contents("$d/$f", 'x'); }
$paths = [];
$nested = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($d));
foreach (new RegexIterator($nested, '/^.+\.html$/i', RecursiveRegexIterator::GET_MATCH) as $path => $match) {
    $paths[] = substr($path, strlen($d) + 1) . '=' . count($match);
}
sort($paths);
echo implode(' ', $paths), "\n";
foreach (['index.html', 'single.html', 'parts/header.html', 'notes.txt'] as $f) { unlink("$d/$f"); }
rmdir("$d/parts"); rmdir($d);
