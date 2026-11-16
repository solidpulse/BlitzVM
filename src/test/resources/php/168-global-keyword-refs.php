<?php
$g = 10;

function bumpGlobal() {
    global $g;
    $g += 5;
    return $GLOBALS['g'];
}
echo "bump=" . bumpGlobal() . ",$g
";

function rebindLocalGlobalRef() {
    global $g;
    $local = 999;
    $g = &$local;
    $local = 888;
    return $g;
}
echo "local_rebind=" . rebindLocalGlobalRef() . ",outer=$g
";

function unsetLocalGlobalImport() {
    global $g;
    unset($g);
    return isset($g) ? 'set' : 'unset';
}
echo "local_unset=" . unsetLocalGlobalImport() . ",outer=$g
";

function rebindViaGlobalsArray() {
    static $shared = 77;
    $GLOBALS['g'] = &$shared;
    $shared = 88;
}
rebindViaGlobalsArray();
echo "globals_ref=$g
";

$alias = &$GLOBALS['g'];
$alias = 321;
echo "alias=$g
";
