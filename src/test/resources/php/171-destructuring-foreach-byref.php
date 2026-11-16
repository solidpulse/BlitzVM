<?php
$rows = [
    [1, 'Alice'],
    [2, 'Bob'],
];
foreach ($rows as [$id, $name]) {
    echo "$id:$name
";
}

$records = [
    'u1' => ['id' => 10, 'score' => 95],
    'u2' => ['id' => 20, 'score' => 88],
];
foreach ($records as $key => ['id' => $rid, 'score' => $score]) {
    echo "$key=>$rid=$score
";
}

$src = [10, [20, 30]];
[&$r0, [, &$r2]] = $src;
$r0 = 100;
$r2 = 300;
unset($r0, $r2);
echo "ref_dest={$src[0]},{$src[1][0]},{$src[1][1]}
";

$pairs = [[1, 2], [3, 4]];
foreach ($pairs as [&$first, $second]) {
    $first += $second * 10;
}
unset($first);
echo "foreach_ref={$pairs[0][0]},{$pairs[1][0]}
";
