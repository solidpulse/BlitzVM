<?php
function append_generator() {
    $s = ''; $s .= 'a'; yield $s;
    $s .= 'b'; $copy = $s; yield $copy;
    $s .= yield 'send'; yield $s;
    $s .= 'd'; return $s;
}
$g = append_generator(); echo $g->current(), "\n";
$g->next(); $snapshot = $g->current(); echo $snapshot, "\n";
$g->next(); echo $g->current(), "\n";
echo $g->send('c'), '|', $snapshot, "\n";
$g->next(); echo $g->getReturn(), "\n";
function append_ref_generator(&$s) {
    $s .= 'a'; yield $s; $s .= 'b'; yield $s; $s .= 'c';
}
$s = ''; $g = append_ref_generator($s);
foreach ($g as $value) { echo $value, '|', $s, "\n"; }
echo $s, "\n";
function dynamic_generator() {
    extract(['s' => '']); $s .= 'x'; yield get_defined_vars()['s'];
    $s .= 'y'; yield compact('s')['s'];
}
foreach (dynamic_generator() as $value) { echo $value, "\n"; }
