<?php
echo basename(__FILE__), "\n";
echo basename(__DIR__), "\n";
var_dump(dirname(__FILE__) === __DIR__);
var_dump(__LINE__);
var_dump(__FUNCTION__);
var_dump(__CLASS__ === '');
var_dump(__METHOD__ === '');
var_dump(__NAMESPACE__ === '');

// Case-insensitivity of magic constants
var_dump(basename(__file__) === basename(__FILE__));
var_dump(__LiNe__);

function myMagicFn() {
    var_dump(__FUNCTION__, __function__, __LINE__);
}
myMagicFn();
var_dump(__FUNCTION__);

// Magic constants inside an included file
include __DIR__ . '/inc/magic_child.php';
