<?php
// Named-argument binding errors, as php reports them.
$words = ['a', 'b'];
$calls = [
    fn() => str_pad('x', 3, wat: 1),
    fn() => str_pad('x', 3, length: 2),
    fn() => str_pad('x', 3, pad_string: '-', pad_string: '+'),
    fn() => str_pad(string: 'x'),
    fn() => str_pad(pad_string: '-'),
    fn() => array_keys($words, strict: true),
    fn() => array_slice(array: $words, length: 1),
    fn() => preg_match('/a/', 'a', matches: 5),
    fn() => sort(array: [3, 1, 2]),
    fn() => str_replace('a', 'b', 'x', count: 5),
    fn() => sprintf(format: '%s', values: 'a'),
    fn() => array_push(array: $words, values: 1),
    fn() => implode(separator: ','),
    fn() => str_contains(haystack: 'abc', wat: 'b'),
];
foreach ($calls as $i => $call) {
    try {
        $call();
        echo $i, ": no error\n";
    } catch (Throwable $e) {
        echo $i, ': ', get_class($e), ': ', $e->getMessage(), "\n";
    }
}
