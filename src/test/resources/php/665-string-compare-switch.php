<?php
// String comparison and switch with numeric strings: loose comparison converts only numeric text.
set_error_handler(function ($no, $msg) { echo "warning: $msg\n"; return true; });

var_dump("1e1" == "10");
var_dump("10" == "1e1");
var_dump("1e1" === "10");
var_dump("abc" == 0);
var_dump(0 == "abc");
var_dump("1abc" == 1);
var_dump("" == 0);
var_dump("0" == false);
var_dump("10" == 10);
var_dump("010" == "10");
var_dump("1.0" == "1");
var_dump("abc" == "ABC");
var_dump("abc" < "abd");

function pick($value) {
    switch ($value) {
        case "10":
            return "ten";
        case "1e1":
            return "scientific";
        case 1:
            return "one";
        case "abc":
            return "letters";
        default:
            return "other";
    }
}
echo pick("10"), "\n";
echo pick("1e1"), "\n";
echo pick(1), "\n";
echo pick("1.0"), "\n";
echo pick("abc"), "\n";
echo pick("xyz"), "\n";

function pickInt($value) {
    switch ($value) {
        case 0:
            return "zero";
        case 1:
            return "one";
        default:
            return "other";
    }
}
echo pickInt("0"), "\n";
echo pickInt("1"), "\n";
echo pickInt("abc"), "\n";
echo pickInt("1abc"), "\n";

var_dump(in_array("1e1", ["10"], false));
var_dump(in_array("1e1", ["10"], true));
var_dump(array_search("10", ["1e1", "x"], false));
