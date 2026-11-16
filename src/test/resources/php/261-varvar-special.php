<?php
$g = 2;
function specials() {
    $name = 'g'; global $$name; $$name += 3;
    echo "$g\n";
    $_GET['global'] = 1;
    $name = '_GET'; $$name = ['local' => 2];
    echo json_encode($_GET), ':', json_encode($$name), "\n";
    $name = 'GLOBALS';
    extract(['GLOBALS' => ['local' => 3]]);
    echo json_encode($$name), ':', $GLOBALS['g'], "\n";
}
specials(); echo "$g\n";
class SpecialThis {
    public $p = 6;
    function f() {
        $name = 'this';
        echo $$name->p, ':', isset($$name) ? 'set' : 'unset', ':', empty($$name) ? 'empty' : 'nonempty', "\n";
        try { $$name = null; } catch (Error $e) { echo $e->getMessage(), "\n"; }
        try { unset($$name); } catch (Error $e) { echo $e->getMessage(), "\n"; }
    }
}
(new SpecialThis)->f();
$name = 'GLOBALS'; $$name = ['dynamic' => 7];
$$name['dynamic']++;
echo json_encode($$name), ':', $GLOBALS['g'], "\n";
unset($$name); echo isset($$name) ? 'set' : 'unset', "\n";
