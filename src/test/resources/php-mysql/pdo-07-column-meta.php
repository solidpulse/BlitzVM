<?php
require __DIR__ . '/pdo_connect.php';

$p = blitz_pdo([PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$p->exec('DROP TABLE IF EXISTS pdo_meta');
$p->exec('CREATE TABLE pdo_meta (
  ti TINYINT, t1 TINYINT(1), si SMALLINT, mi MEDIUMINT, i INT, bi BIGINT, bu BIGINT UNSIGNED,
  f FLOAT, dbl DOUBLE, dec1 DECIMAL(10,2), bit8 BIT(8), bl BLOB, tx TEXT, vc VARCHAR(7),
  ch CHAR(3), en ENUM(\'a\',\'b\'), se SET(\'x\',\'y\'), y YEAR, js JSON, dt DATETIME,
  da DATE, tm TIME, ts TIMESTAMP NULL
) DEFAULT CHARSET=utf8mb4');
$p->exec("INSERT INTO pdo_meta VALUES (1,1,2,3,4,9223372036854775807,18446744073709551615,1.5,0.1,12.34,b'10101010','blob','text','value','abc','b','x,y',2024,'{\"a\":1}','2024-01-02 03:04:05','2024-01-02','12:34:56','2024-01-02 03:04:05')");
$p->exec('DROP TABLE IF EXISTS pdo_meta_unique');
$p->exec('CREATE TABLE pdo_meta_unique (u INT NOT NULL UNIQUE, a INT, b INT, c BLOB, KEY(a), KEY(b), KEY(c)) ENGINE=InnoDB');

$s = $p->query('SELECT * FROM pdo_meta');
blitz_show('column-count', $s->columnCount());
for ($i = 0; $i < $s->columnCount(); $i++) {
    blitz_show('meta-' . $i, $s->getColumnMeta($i));
}
blitz_show('out-of-range', $s->getColumnMeta($s->columnCount()));

echo "== keys and flags ==\n";
$s = $p->query('SELECT * FROM pdo_meta_unique');
for ($i = 0; $i < $s->columnCount(); $i++) {
    $m = $s->getColumnMeta($i);
    blitz_show('flags-' . $i, [$m['name'], $m['flags']]);
}

echo "== expressions ==\n";
$s = $p->query("SELECT 1+1, COUNT(*) AS c, NOW() AS n, 'x' AS s, 1.5 AS f, NULL AS z");
for ($i = 0; $i < $s->columnCount(); $i++) {
    blitz_show('expr-' . $i, $s->getColumnMeta($i));
}

echo "== aliases and case ==\n";
$p->setAttribute(PDO::ATTR_CASE, PDO::CASE_UPPER);
$s = $p->query('SELECT vc AS MixEd, i AS Ident FROM pdo_meta');
blitz_show('upper-0', $s->getColumnMeta(0));
blitz_show('upper-1', $s->getColumnMeta(1));
$p->setAttribute(PDO::ATTR_CASE, PDO::CASE_LOWER);
$s = $p->query('SELECT vc AS MixEd FROM pdo_meta');
blitz_show('lower', $s->getColumnMeta(0));
$p->setAttribute(PDO::ATTR_CASE, PDO::CASE_NATURAL);

echo "== prepared metadata ==\n";
foreach ([true, false] as $emu) {
    $p->setAttribute(PDO::ATTR_EMULATE_PREPARES, $emu);
    echo 'emu=', (int) $emu, "\n";
    $s = $p->prepare('SELECT i, vc, dec1, bit8 FROM pdo_meta WHERE i = ?');
    $s->execute([4]);
    blitz_show('before', []);
    for ($i = 0; $i < $s->columnCount(); $i++) {
        blitz_show('prep-' . $i, $s->getColumnMeta($i));
    }
}
$p->setAttribute(PDO::ATTR_EMULATE_PREPARES, true);

$p->exec('DROP TABLE IF EXISTS pdo_meta');
$p->exec('DROP TABLE IF EXISTS pdo_meta_unique');
