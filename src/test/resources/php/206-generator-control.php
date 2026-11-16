<?php
function control() {
    for ($i = 0; $i < 5; $i++) {
        if ($i === 1) { continue; }
        if ($i === 4) { break; }
        if ($i % 2) { yield "odd:$i"; } else { yield "even:$i"; }
        echo "after:$i|";
    }
    $n = 0;
    while ($n < 2) { yield "while:$n"; $n++; }
    do { yield "do:$n"; $n--; } while ($n > 0);
    foreach (['a' => 1, 'b' => 2] as $key => $value) {
        yield "$key:$value";
        yield "$key:again";
    }
    switch (2) {
        case 1: yield 'wrong'; break;
        case 2: yield 'two';
        default: yield 'fallthrough';
    }
    return 'done';
}
$g = control();
echo json_encode(iterator_to_array($g)), "\n", $g->getReturn(), "\n";
