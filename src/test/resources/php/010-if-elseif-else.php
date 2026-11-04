<?php
function test_grade($score) {
    if ($score >= 90) {
        return "A";
    } elseif ($score >= 80) {
        return "B";
    } elseif ($score >= 70) {
        return "C";
    } else {
        return "F";
    }
}

echo test_grade(95), "\n";
echo test_grade(85), "\n";
echo test_grade(75), "\n";
echo test_grade(50), "\n";
