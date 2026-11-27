<?php
// fprintf and vfprintf format like sprintf and write to a stream; vprintf prints. All return
// the formatted length, even when the stream refuses the write.
$f = fopen('php://memory', 'w+');
var_dump(fprintf($f, '%s=%05.1f|', 'pi', 3.14159));
var_dump(vfprintf($f, '%2$s-%1$d|%%|', [7, 'x']));
rewind($f);
var_dump(stream_get_contents($f));
var_dump(vprintf("%x %o %b %'*8s\n", [255, 8, 5, 'pad']));
$r = fopen('php://memory', 'r');
var_dump(fprintf($r, 'abc'));
var_dump(fprintf($f, ''));
