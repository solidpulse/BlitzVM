<?php
echo str_pad("x", 4, pad_string: "-"), "\n";
echo str_pad(string: "x", length: 5, pad_type: 0, pad_string: "-"), "\n";
echo str_pad("x", 5, pad_type: 0), "\n";
echo substr(string: "abcdef", offset: 2), "\n";
echo substr("abcdef", offset: 1, length: 2), "\n";
echo implode(separator: ",", array: [1, 2, 3]), "\n";
var_dump(array_slice(array: [1, 2, 3, 4], offset: 1, length: 2));

try { str_pad("x", 4, string: "y"); } catch (Throwable $e) { echo get_class($e), ": ", $e->getMessage(), "\n"; }
try { str_pad("x", 4, junk: 1); } catch (Throwable $e) { echo get_class($e), ": ", $e->getMessage(), "\n"; }
try { str_pad("x", pad_string: "-"); } catch (Throwable $e) { echo get_class($e), ": ", $e->getMessage(), "\n"; }
try { str_pad(pad_string: "-"); } catch (Throwable $e) { echo get_class($e), ": ", $e->getMessage(), "\n"; }
try { str_pad(string: "x"); } catch (Throwable $e) { echo get_class($e), ": ", $e->getMessage(), "\n"; }
try { array_keys(array: [1, 2], strict: true); } catch (Throwable $e) { echo get_class($e), ": ", $e->getMessage(), "\n"; }

$a = [1, 2];
try { array_push($a, values: 3); } catch (Throwable $e) { echo get_class($e), ": ", $e->getMessage(), "\n"; }
try { sprintf(format: "%s", values: "x"); } catch (Throwable $e) { echo get_class($e), ": ", $e->getMessage(), "\n"; }
var_dump(max(value: [1, 2]));

$m = 0;
preg_match('/a/', 'abc', matches: $m);
var_dump($m);
preg_match(pattern: '/b/', subject: 'abc', matches: $n, offset: 1);
var_dump($n);
try { preg_match('/a/', 'abc', matches: []); } catch (Throwable $e) { echo get_class($e), ": ", $e->getMessage(), "\n"; }

$numbers = [3, 1, 2];
sort(array: $numbers);
var_dump($numbers);
