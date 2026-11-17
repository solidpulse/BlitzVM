<?php
// Bytecode 17b: switch with a default in the middle and fall-through, continue 2 out
// of a switch, file-scope static variables, casts, unary operators, print, and exit
// skipping an enclosing finally block.
$log = [];
for ($i = 0; $i < 4; $i++) {
    switch ($i) {
        case 0:
            $log[] = "zero";
            break;
        default:
            $log[] = "default";
        case 2:
            $log[] = "two";
            if ($i === 1) {
                continue 2;
            }
            break;
        case 3:
            $log[] = "three";
    }
    $log[] = "tail$i";
}
echo implode(",", $log), "\n";

static $top = 5;
function bump_top() {
    global $top;
    $top++;
}
bump_top();
bump_top();
echo $top, "\n";

echo (int) "12abc", ",", (float) "1.5x", ",", (string) 7, ",",
     (int) true, ",", ~5, ",", +"4", "\n";
print "printed\n";

function leave_now() {
    exit("bye\n");
}
try {
    echo "in-try\n";
    leave_now();
} finally {
    echo "finally-skipped\n";
}
