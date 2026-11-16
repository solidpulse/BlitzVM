<?php
foreach (["é", "😀", "\xFF\0\xC3", ['clé' => "é😀\xFF\0", 2 => 'café']] as $v) {
    $s = serialize($v);
    echo bin2hex($s), "\n";
    var_dump(unserialize($s) === $v);
}
var_dump(unserialize('s:2:"é";'), unserialize('s:4:"😀";'));
echo serialize("\xFF\0"), "\n";
