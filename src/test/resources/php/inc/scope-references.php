<?php
$a = &$b;
$a++;
global $g;
$g += 5;
static $n = 0;
$n++;
$new = &$a;
