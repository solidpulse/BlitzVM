<?php
// explode: every limit sign, limit 0, empty and multi-byte separators, separators at the
// edges, and a separator longer than the subject.
function show($label, $array) {
    echo $label, '=';
    foreach ($array as $key => $value) {
        echo $key, ':', bin2hex($value), ' ';
    }
    echo "\n";
}

foreach ([0, 1, 2, 3, 5, 9, -1, -2, -3, -4, -9, PHP_INT_MAX, PHP_INT_MIN] as $limit) {
    show('comma' . $limit, explode(',', 'a,b,c,d', $limit));
}

show('empty', explode(',', ''));
show('empty-neg', explode(',', '', -1));
show('empty-parts', explode(',', ','));
show('empty-parts-neg', explode(',', ',', -1));
show('double', explode(',,', 'a,,b,'));
show('multichar', explode('::', 'a::b::::c::'));
show('longsep', explode('abcdef', 'abc'));
show('same', explode('a', 'a'));
show('same-neg', explode('a', 'aaaa', -2));
show('space', explode(' ', ' leading and trailing '));
show('notfound-limit', explode('|', 'abc', 4));
show('bytes', explode("\0", "a\0b\0c"));
show('limit-one', explode(',', 'a,b,c', 1));
show('limit-one-empty', explode(',', '', 1));
