<?php
$count = 0;
$prefix = "item";

function increment() {
    global $count;
    $count++;
}

function get_prefixed_id($id) {
    global $prefix;
    return $prefix . "_" . $id;
}

increment();
increment();
increment();
echo "Count: ", $count, "\n";
echo get_prefixed_id(42), "\n";
