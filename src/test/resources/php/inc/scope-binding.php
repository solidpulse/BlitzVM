<?php
$a = &$b;
extract(['a' => 8]);
$file_value = $a;
$new = 3;
$saved = &$new;
unset($new);
$new = 4;
echo "$saved:$new\n";
