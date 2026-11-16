<?php
function append_scope() {
    $s = 'start'; $s .= '-local';
    $copy = $s; $result = include __DIR__ . '/inc/append-scope.php';
    $s .= '-after'; echo $copy, '|', implode('|', $result), '|', $s, "\n";
    $vars = get_defined_vars(); $s .= '!'; echo $vars['s'], '|', $s, "\n";
    $name = 's'; $$name .= '?'; echo compact($name)['s'], "\n";
    $from = ['s' => 'extract']; extract($from); $s .= '-value'; echo $from['s'], '|', $s, "\n";
    extract($from, EXTR_REFS); $s .= '-ref'; echo $from['s'], '|', $s, "\n";
    eval('$s .= "-eval";'); echo $s, "\n";
    $ref =& $s; $snapshot = get_defined_vars(); $s .= '-alias';
    echo $snapshot['s'], '|', $snapshot['ref'], "\n";
    $lateName = 'late'; $$lateName = 'late'; $$lateName .= '-append';
    echo compact($lateName)[$lateName], "\n";
}
append_scope();
