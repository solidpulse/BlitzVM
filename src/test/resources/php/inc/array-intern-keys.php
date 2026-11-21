<?php
// Literal keys written in one file and read in another: php interns string literals, so the
// key instances are the same and the hash probe's identity check hits.

function intern_store(&$a) {
    $a["shared-literal"] = 42;
    $a["another-literal"] = 7;
    $a["5"] = "five";
    $a["05"] = "zero five";
}

function intern_read($a) {
    return $a["shared-literal"] + $a["another-literal"];
}

function intern_read_one($a, $key) {
    return $a[$key];
}

$INTERN_LITERAL = ["shared-literal" => 1, "another-literal" => 2];
