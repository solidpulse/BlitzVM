<?php
function describe_number($n) {
    switch ($n) {
        case 1:
            echo "one\n";
            break;
        case 2:
        case 3:
            echo "two or three\n";
            break;
        case 4:
            echo "four\n";
            break;
        default:
            echo "other\n";
            break;
    }
}

describe_number(1);
describe_number(2);
describe_number(3);
describe_number(4);
describe_number(99);
