<?php
// Semantics WordPress's installer and front page depend on (found running WordPress).
// 1. `global $x;` at file level creates the global as null: no warning on read.
global $wp_filter;
var_dump($wp_filter, array_key_exists('wp_filter', $GLOBALS));
// 2. str_replace / str_ireplace count argument.
$out = str_replace(['%0d', '%0a'], '', 'a%0d%0ab%0D', $count);
var_dump($out, $count);
$out = str_ireplace('%0D', '', 'a%0db%0D', $count);
var_dump($out, $count);
// 3. $this by reference: argument, array element, assignment; all temporaries.
function set_upgrader(&$u) { $u->seen = true; $u = null; }
function walk_ref(array $hooks) { $hooks[0]->v++; }
class Upgrader {
    public $seen = false; public $v = 0;
    function init() { set_upgrader($this); var_dump($this instanceof Upgrader, $this->seen); }
    function hooks() { walk_ref([&$this]); $r = &$this; $r = 5; var_dump($this->v, $this instanceof Upgrader); }
    function dynamic() { $pieces = ['where']; $where = 'w'; $a = [compact($pieces), &$this]; var_dump($a[0], $a[1] === $this); }
}
$u = new Upgrader; $u->init(); $u->hooks(); $u->dynamic();
// 4. Diagnostics name the file that raised them, not the entry script.
set_error_handler(function ($no, $msg, $file, $line) { echo basename($file), ":$line $msg\n"; return true; });
include __DIR__ . '/inc/warn-in-include.php';
echo $undefinedHere;
