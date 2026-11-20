<?php
require __DIR__ . '/pdo_connect.php';

$p = blitz_pdo([PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$p->exec('DROP TABLE IF EXISTS pdo_err');
$p->exec('CREATE TABLE pdo_err (id INT PRIMARY KEY, v VARCHAR(3) NOT NULL, d DATE) ENGINE=InnoDB');
$p->exec("INSERT INTO pdo_err VALUES (1,'a','2024-01-01')");

echo "== sql errors ==\n";
blitz_error('missing-table', fn() => $p->query('SELECT * FROM nope'));
blitz_error('missing-column', fn() => $p->query('SELECT nope FROM pdo_err'));
blitz_error('duplicate', fn() => $p->exec("INSERT INTO pdo_err VALUES (1,'a','2024-01-01')"));
blitz_error('too-long', fn() => $p->exec("INSERT INTO pdo_err VALUES (2,'abcd','2024-01-01')"));
blitz_error('not-null', fn() => $p->exec("INSERT INTO pdo_err VALUES (3,NULL,'2024-01-01')"));
blitz_error('bad-date', fn() => $p->exec("INSERT INTO pdo_err VALUES (4,'a','2024-13-01')"));
blitz_error('syntax', fn() => $p->exec('SELEC 1'));
blitz_error('exec-missing', fn() => $p->prepare('SELECT * FROM nope')->execute());
blitz_error('native-prepare-missing', function () use ($p) {
    $p->setAttribute(PDO::ATTR_EMULATE_PREPARES, false);
    try {
        return $p->prepare('SELECT * FROM nope');
    } finally {
        $p->setAttribute(PDO::ATTR_EMULATE_PREPARES, true);
    }
});
blitz_error('empty-query', fn() => $p->query(''));
blitz_error('empty-exec', fn() => $p->exec(''));
blitz_error('empty-prepare', fn() => $p->prepare(''));

echo "== silent mode ==\n";
$p->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_SILENT);
blitz_show('query', $p->query('SELECT * FROM nope'));
blitz_show('query-info', [$p->errorCode(), $p->errorInfo()]);
blitz_show('ok', $p->query('SELECT 1')->fetchColumn());
blitz_show('cleared', [$p->errorCode(), $p->errorInfo()]);
blitz_show('exec', $p->exec('SELEC 1'));
blitz_show('exec-info', [$p->errorCode(), $p->errorInfo()]);
$s = $p->prepare('SELECT * FROM nope');
blitz_show('stmt-execute', $s->execute());
blitz_show('stmt-info', [$s->errorCode(), $s->errorInfo()]);
blitz_show('pdo-unchanged', [$p->errorCode()]);
blitz_show('prepare-native', function () use ($p) {
    $p->setAttribute(PDO::ATTR_EMULATE_PREPARES, false);
    try {
        return $p->prepare('SELECT * FROM nope');
    } finally {
        $p->setAttribute(PDO::ATTR_EMULATE_PREPARES, true);
    }
});
blitz_show('prepare-info', [$p->errorCode(), $p->errorInfo()]);
$p->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);

echo "== parameter errors ==\n";
foreach ([true, false] as $emu) {
    $p->setAttribute(PDO::ATTR_EMULATE_PREPARES, $emu);
    echo 'emu=', (int) $emu, "\n";
    blitz_error('repeated', fn() => $p->prepare('SELECT :x AS a, :x AS b')->execute(['x' => 3]));
    blitz_error('missing', fn() => $p->prepare('SELECT ?, ?')->execute([1]));
    blitz_error('unknown', fn() => $p->prepare('SELECT ?')->execute(['x' => 1]));
    blitz_error('extra', fn() => $p->prepare('SELECT 1')->execute([1]));
    blitz_error('bind-zero', fn() => $p->prepare('SELECT ?')->bindValue(0, 1));
    blitz_error('mixed', function () use ($p) {
        $s = $p->prepare('SELECT ?, :x');
        return $s->execute([1, 'x' => 2]);
    });
    blitz_error('string-key', fn() => $p->prepare('SELECT ?')->execute(['1' => 2]));
    blitz_error('bind-out-of-range', function () use ($p) {
        $s = $p->prepare('SELECT ?');
        $s->bindValue(2, 1);
        return $s->execute();
    });
    blitz_error('empty-params', function () use ($p) {
        $s = $p->prepare('SELECT ? AS v');
        $s->bindValue(1, 'a');
        return $s->execute([]);
    });
}
$p->setAttribute(PDO::ATTR_EMULATE_PREPARES, true);

echo "== transaction ==\n";
$p->exec('DROP TABLE IF EXISTS pdo_tx');
$p->exec('CREATE TABLE pdo_tx (id INT) ENGINE=InnoDB');
blitz_show('in0', $p->inTransaction());
blitz_show('begin', $p->beginTransaction());
blitz_show('in1', $p->inTransaction());
blitz_show('insert', $p->exec('INSERT INTO pdo_tx VALUES (1)'));
blitz_show('rollback', $p->rollBack());
blitz_show('in2', $p->inTransaction());
blitz_show('count', $p->query('SELECT COUNT(*) FROM pdo_tx')->fetchColumn());
blitz_error('rollback-none', fn() => $p->rollBack());
blitz_error('commit-none', fn() => $p->commit());
blitz_show('begin2', $p->beginTransaction());
blitz_error('begin-again', fn() => $p->beginTransaction());
blitz_show('insert2', $p->exec('INSERT INTO pdo_tx VALUES (2)'));
blitz_show('commit2', $p->commit());
blitz_show('count2', $p->query('SELECT COUNT(*) FROM pdo_tx')->fetchColumn());
blitz_show('autocommit-off', $p->setAttribute(PDO::ATTR_AUTOCOMMIT, false));
blitz_show('in3', $p->inTransaction());
blitz_show('insert3', $p->exec('INSERT INTO pdo_tx VALUES (3)'));
blitz_show('commit3', $p->commit());
blitz_show('count3', $p->query('SELECT COUNT(*) FROM pdo_tx')->fetchColumn());
blitz_show('autocommit-on', $p->setAttribute(PDO::ATTR_AUTOCOMMIT, true));
blitz_show('in4', $p->inTransaction());

echo "== attribute errors ==\n";
$p->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_SILENT);
blitz_show('set-timeout', $p->setAttribute(PDO::ATTR_TIMEOUT, 5));
blitz_show('get-timeout', $p->getAttribute(PDO::ATTR_TIMEOUT));
blitz_show('set-persistent', $p->setAttribute(PDO::ATTR_PERSISTENT, true));
blitz_show('get-persistent', $p->getAttribute(PDO::ATTR_PERSISTENT));
blitz_show('set-server-version', $p->setAttribute(PDO::ATTR_SERVER_VERSION, 'x'));
blitz_show('set-unknown', $p->setAttribute(9999, 1));
blitz_show('get-cursor', $p->getAttribute(PDO::ATTR_CURSOR));
$s = $p->query('SELECT 1');
blitz_show('stmt-get-errmode', $s->getAttribute(PDO::ATTR_ERRMODE));
blitz_show('stmt-get-emulate', $s->getAttribute(PDO::ATTR_EMULATE_PREPARES));
blitz_show('stmt-set', $s->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_SILENT));
$p->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
blitz_error('case-bad', fn() => $p->setAttribute(PDO::ATTR_CASE, 99));
blitz_error('errmode-bad', fn() => $p->setAttribute(PDO::ATTR_ERRMODE, 99));

$p->exec('DROP TABLE IF EXISTS pdo_err');
$p->exec('DROP TABLE IF EXISTS pdo_tx');
