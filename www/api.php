<?php
// A CPU-heavier API handler of the kind frameworks spend time in: decode and validate a request
// payload, build and transform records (map/filter/sort/group), format values, encode the response.
$n = (int) ($_GET['n'] ?? 300);
$seed = 42;
$rand = function (int $lo, int $hi) use (&$seed): int {
    $seed = ($seed * 1103515245 + 12345) % 2147483648;
    return $lo + $seed % ($hi - $lo + 1);
};
$payload = [];
for ($i = 0; $i < $n; $i++) {
    $payload[] = [
        'id' => $i + 1,
        'email' => 'user' . $rand(1, 99999) . '@example.' . (['com', 'org', 'net'][$i % 3]),
        'name' => ucwords('customer ' . dechex($rand(4096, 65535)) . ' smith'),
        'amount' => $rand(100, 999999) / 100,
        'tags' => array_slice(['new', 'vip', 'eu', 'us', 'trial', 'b2b'], $i % 4, 2 + $i % 3),
        'created' => 1767225600 + $i * 3571,
    ];
}
$request = json_decode(json_encode(['filters' => ['min' => 50], 'items' => $payload]), true);

$errors = 0;
$valid = array_filter($request['items'], function (array $it) use (&$errors) {
    $ok = preg_match('/^[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}$/i', $it['email'])
        && filter_var($it['amount'], FILTER_VALIDATE_FLOAT) !== false
        && strlen(trim($it['name'])) > 0;
    if (!$ok) { $errors++; }
    return $ok;
});
$rows = array_map(fn(array $it) => [
    'id' => $it['id'],
    'name' => htmlspecialchars($it['name']),
    'domain' => substr(strrchr($it['email'], '@'), 1),
    'amount' => number_format($it['amount'], 2, '.', ','),
    'raw' => $it['amount'],
    'tags' => implode(',', $it['tags']),
    'month' => date('Y-m', $it['created']),
    'key' => substr(md5($it['email'] . $it['id']), 0, 12),
], array_values(array_filter($valid, fn($it) => $it['amount'] >= $request['filters']['min'])));
usort($rows, fn($a, $b) => [$b['raw'], $a['id']] <=> [$a['raw'], $b['id']]);
$byDomain = [];
foreach ($rows as $r) {
    $byDomain[$r['domain']]['count'] = ($byDomain[$r['domain']]['count'] ?? 0) + 1;
    $byDomain[$r['domain']]['sum'] = ($byDomain[$r['domain']]['sum'] ?? 0) + $r['raw'];
}
ksort($byDomain);
$summary = sprintf('%d rows, %d invalid, top %s', count($rows), $errors, $rows[0]['name'] ?? '-');
header('Content-Type: application/json');
echo json_encode(['summary' => $summary, 'groups' => $byDomain, 'rows' => array_slice($rows, 0, 50)]);
