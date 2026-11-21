<?php
function side(string $label): int {
    echo $label;
    return 1;
}

(void) side('a');
(void) side('b') + side('c');
(void) $assigned = 42;
echo "\n", $assigned, "\n";
(void) print "printed\n";
$value = 7;
(void) $value++;
echo $value, "\n";
if (true) {
    (void) side('inside');
    echo "\n";
}
for ($i = 0; $i < 1; $i++) {
    (void) $i;
}
echo "done\n";
