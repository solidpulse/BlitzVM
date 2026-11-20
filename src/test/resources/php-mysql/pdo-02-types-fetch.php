<?php
require __DIR__ . '/pdo_connect.php';

$p = blitz_pdo([PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$p->exec('DROP TABLE IF EXISTS pdo_types');
$p->exec('CREATE TABLE pdo_types (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  i INT, ti TINYINT(1), si SMALLINT, mi MEDIUMINT, bi BIGINT, bu BIGINT UNSIGNED,
  d DECIMAL(10,3), f FLOAT, dbl DOUBLE, vc VARCHAR(20) NOT NULL DEFAULT \'\',
  txt LONGTEXT, dt DATETIME NOT NULL DEFAULT \'0000-00-00 00:00:00\', da DATE, tm TIME,
  ts TIMESTAMP NULL, y YEAR, b BLOB, e ENUM(\'a\',\'b\'), s SET(\'x\',\'y\'), bit8 BIT(8), js JSON
) DEFAULT CHARSET=utf8mb4');
$p->exec("INSERT INTO pdo_types (i, ti, si, mi, bi, bu, d, f, dbl, vc, txt, dt, da, tm, ts, y, b, e, s, bit8, js)
  VALUES (-5, 1, 300, -70000, 9223372036854775807, 18446744073709551615, 12.5, 1.1, 0.1,
          'héllo', 'long ✓ text', '2024-02-29 13:45:01', '2024-02-29', '-12:30:00',
          '2024-01-01 00:00:00', 2024, 'bytes', 'b', 'x,y', b'10101010', '{\"a\": 1}')");
$p->exec("INSERT INTO pdo_types (vc) VALUES ('x')");
blitz_show('insert-id', $p->lastInsertId());

echo "== default fetch (BOTH) ==\n";
$s = $p->query('SELECT id, i, ti, si, mi, bi, bu, d, f, dbl FROM pdo_types ORDER BY id');
blitz_show('row1', $s->fetch());
blitz_show('row2', $s->fetch());
blitz_show('row3', $s->fetch());
blitz_show('counts', [$s->rowCount(), $s->columnCount()]);

echo "== strings and dates ==\n";
$s = $p->query('SELECT vc, txt, dt, da, tm, ts, y, b, e, s, bit8, js FROM pdo_types WHERE id = 1');
$row = $s->fetch(PDO::FETCH_ASSOC);
$row['b'] = bin2hex($row['b']);
blitz_show('special', $row);

echo "== nulls ==\n";
blitz_show('nulls', $p->query('SELECT i, vc, dt, b FROM pdo_types WHERE id = 2')->fetch(PDO::FETCH_ASSOC));

echo "== modes ==\n";
foreach ([true, false] as $emu) {
    $p->setAttribute(PDO::ATTR_EMULATE_PREPARES, $emu);
    echo 'emu=', (int) $emu, "\n";
    $s = $p->prepare('SELECT id, i, ti, si, mi, bi, bu, d, f, dbl FROM pdo_types WHERE id = ?');
    $s->execute([1]);
    blitz_show('types', $s->fetch(PDO::FETCH_ASSOC));
    $s = $p->prepare('SELECT vc, txt, dt, b, bit8 FROM pdo_types WHERE id = ?');
    $s->execute([1]);
    $r = $s->fetch(PDO::FETCH_ASSOC);
    $r['b'] = bin2hex($r['b']);
    blitz_show('special', $r);
}
$p->setAttribute(PDO::ATTR_EMULATE_PREPARES, true);

echo "== stringify ==\n";
$p->setAttribute(PDO::ATTR_STRINGIFY_FETCHES, true);
blitz_show('stringified', $p->query('SELECT id, i, f, dbl, d, bu FROM pdo_types WHERE id = 1')->fetch(PDO::FETCH_ASSOC));
$p->setAttribute(PDO::ATTR_EMULATE_PREPARES, false);
$s = $p->prepare('SELECT id, i, f, dbl, d FROM pdo_types WHERE id = ?');
$s->execute([1]);
blitz_show('stringified-native', $s->fetch(PDO::FETCH_ASSOC));
$p->setAttribute(PDO::ATTR_EMULATE_PREPARES, true);
$p->setAttribute(PDO::ATTR_STRINGIFY_FETCHES, false);

echo "== case ==\n";
foreach ([PDO::CASE_NATURAL => 'natural', PDO::CASE_UPPER => 'upper', PDO::CASE_LOWER => 'lower'] as $mode => $label) {
    $p->setAttribute(PDO::ATTR_CASE, $mode);
    blitz_show($label, ['assoc' => $p->query('SELECT id AS Id, vc AS Name FROM pdo_types WHERE id = 1')->fetch(PDO::FETCH_ASSOC),
        'obj' => get_object_vars($p->query('SELECT id AS Id, vc AS Name FROM pdo_types WHERE id = 1')->fetch(PDO::FETCH_OBJ)),
        'meta' => $p->query('SELECT id AS Id, vc AS Name FROM pdo_types WHERE id = 1')->getColumnMeta(0)['name']]);
}
$p->setAttribute(PDO::ATTR_CASE, PDO::CASE_NATURAL);

echo "== oracle nulls ==\n";
foreach ([PDO::NULL_NATURAL => 'natural', PDO::NULL_EMPTY_STRING => 'empty', PDO::NULL_TO_STRING => 'tostring'] as $mode => $label) {
    $p->setAttribute(PDO::ATTR_ORACLE_NULLS, $mode);
    blitz_show($label, $p->query('SELECT i AS a, vc AS b, dt AS c FROM pdo_types ORDER BY id')->fetchAll(PDO::FETCH_ASSOC));
}
$p->setAttribute(PDO::ATTR_ORACLE_NULLS, PDO::NULL_NATURAL);

echo "== default fetch mode attribute ==\n";
$p->setAttribute(PDO::ATTR_DEFAULT_FETCH_MODE, PDO::FETCH_NUM);
blitz_show('query-fetch', $p->query('SELECT id, vc FROM pdo_types WHERE id = 1')->fetch());
blitz_show('override', $p->query('SELECT id, vc FROM pdo_types WHERE id = 1')->fetch(PDO::FETCH_ASSOC));
$p->setAttribute(PDO::ATTR_DEFAULT_FETCH_MODE, PDO::FETCH_OBJ);
blitz_show('object', get_object_vars($p->query('SELECT id, vc FROM pdo_types WHERE id = 1')->fetch()));
$p->setAttribute(PDO::ATTR_DEFAULT_FETCH_MODE, PDO::FETCH_BOTH);

$p->exec('DROP TABLE IF EXISTS pdo_types');
