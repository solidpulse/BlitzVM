<?php
// eval on bytecode: the caller's scope, return values, declarations and parse errors.

function scoped($n) {
    $local = 'outer';
    $r = eval('$local .= "+eval"; $made = $n * 2; return $made + 1;');
    echo $local, ' ', $made, ' ', $r, "\n";
    eval('function fromEval($x) { return "fromEval:" . $x; }');
    echo fromEval($n), "\n";
    try {
        eval('$broken = ;');
    } catch (ParseError $e) {
        echo get_class($e), ': ', $e->getMessage(), ' line ', $e->getLine(), ' ', basename($e->getFile()), "\n";
    }
    foreach ([1, 2] as $i) {
        eval('$sum = ($sum ?? 0) + $i;');
    }
    echo $sum, "\n";
}

function main() {
    scoped(5);
    echo eval('return strtoupper("global");'), "\n";
}

main();
