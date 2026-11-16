<?php
function strings() {
    $name = 'value'; $value = 'hello';
    echo "{$$name}|{${'val' . 'ue'}}|$$name\n";
    $$name = ['p' => 7];
    echo "{$$name['p']}\n";
    $name = 'obj'; $obj = new stdClass; $obj->p = 'property';
    echo "{$$name->p}\n";
    $n = 'name';
    echo $$$n->p, "\n";
}
strings();
