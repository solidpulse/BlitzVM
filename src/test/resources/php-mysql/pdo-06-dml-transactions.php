<?php
require __DIR__ . '/pdo_connect.php';

$p = blitz_pdo([PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$p->exec('DROP TABLE IF EXISTS pdo_dml');
$p->exec('CREATE TABLE pdo_dml (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(20) NOT NULL, amount INT NOT NULL DEFAULT 0) ENGINE=InnoDB');

echo "== exec ==\n";
blitz_show('insert-1', $p->exec("INSERT INTO pdo_dml (name, amount) VALUES ('a', 10)"));
blitz_show('last-id-1', $p->lastInsertId());
blitz_show('insert-2', $p->exec("INSERT INTO pdo_dml (name, amount) VALUES ('b', 20), ('c', 30)"));
blitz_show('last-id-2', $p->lastInsertId());
blitz_show('update-unchanged', $p->exec('UPDATE pdo_dml SET amount = amount'));
blitz_show('update-changed', $p->exec('UPDATE pdo_dml SET amount = amount + 1 WHERE name = \'a\''));
blitz_show('last-id-update', $p->lastInsertId());
$p->query('SELECT COUNT(*) FROM pdo_dml')->fetchColumn();
blitz_show('last-id-select', $p->lastInsertId());
blitz_show('delete', $p->exec("DELETE FROM pdo_dml WHERE name = 'c'"));
blitz_show('last-id-delete', $p->lastInsertId());

echo "== query on dml ==\n";
$s = $p->query("INSERT INTO pdo_dml (name, amount) VALUES ('d', 40)");
blitz_show('insert-stmt', [get_class($s), $s->rowCount(), $s->columnCount(), $s->fetch()]);
blitz_show('last-id-query', $p->lastInsertId());
$s = $p->query("UPDATE pdo_dml SET amount = amount + 1 WHERE name = 'd'");
blitz_show('update-stmt', [$s->rowCount(), $s->columnCount()]);

echo "== prepared dml ==\n";
foreach ([true, false] as $emu) {
    $p->setAttribute(PDO::ATTR_EMULATE_PREPARES, $emu);
    echo 'emu=', (int) $emu, "\n";
    $s = $p->prepare('INSERT INTO pdo_dml (name, amount) VALUES (?, ?)');
    blitz_show('insert', $s->execute(['e', 50]));
    blitz_show('last-id', $p->lastInsertId());
    blitz_show('row-count', $s->rowCount());
    $s = $p->prepare('INSERT INTO pdo_dml (name, amount) VALUES (:n, :a)');
    blitz_show('named', $s->execute(['n' => 'f', 'a' => 60]));
    blitz_show('last-id-2', $p->lastInsertId());
    $s = $p->prepare('UPDATE pdo_dml SET amount = :a WHERE name = :n');
    $s->bindValue(':a', 99, PDO::PARAM_INT);
    $s->bindValue(':n', 'e');
    blitz_show('update', $s->execute());
    blitz_show('row-count-2', $s->rowCount());
    $s = $p->prepare('DELETE FROM pdo_dml WHERE name = ?');
    blitz_show('delete', $s->execute(['f']));
    blitz_show('row-count-3', $s->rowCount());
    $s = $p->prepare('SELECT COUNT(*) FROM pdo_dml');
    $s->execute();
    blitz_show('select-count', [$s->fetchColumn(), $s->rowCount()]);
}

echo "== transactions ==\n";
$p->exec("DELETE FROM pdo_dml");
$p->beginTransaction();
$p->exec("INSERT INTO pdo_dml (name, amount) VALUES ('tx1', 1)");
$id = $p->lastInsertId();
blitz_show('inside', [$p->inTransaction(), $id, $p->query('SELECT COUNT(*) FROM pdo_dml')->fetchColumn()]);
$p->rollBack();
blitz_show('after-rollback', [$p->inTransaction(), $p->query('SELECT COUNT(*) FROM pdo_dml')->fetchColumn()]);

$p->beginTransaction();
$p->exec("INSERT INTO pdo_dml (name, amount) VALUES ('tx2', 2)");
$p->commit();
blitz_show('after-commit', [$p->inTransaction(), $p->query('SELECT COUNT(*) FROM pdo_dml')->fetchColumn()]);
blitz_show('last-id-commit', $p->lastInsertId());

echo "== failed transaction ==\n";
$p->beginTransaction();
try {
    $p->exec("INSERT INTO pdo_dml (name, amount) VALUES ('tx3', 3)");
    $p->exec("INSERT INTO pdo_dml (id, name, amount) VALUES (1, 'dup', 0)");
    $p->commit();
} catch (PDOException $e) {
    blitz_show('caught', [$e->getCode(), $e->errorInfo[0]]);
    $p->rollBack();
}
blitz_show('after-failed', [$p->inTransaction(), $p->query('SELECT COUNT(*) FROM pdo_dml')->fetchColumn()]);

echo "== laravel style ==\n";
$p->beginTransaction();
try {
    $insert = $p->prepare('INSERT INTO pdo_dml (name, amount) VALUES (:name, :amount)');
    $insert->execute(['name' => 'job-1', 'amount' => 5]);
    $jobId = (int) $p->lastInsertId();
    $update = $p->prepare('UPDATE pdo_dml SET amount = :amount WHERE id = :id');
    $update->execute(['amount' => 6, 'id' => $jobId]);
    $p->commit();
} catch (Throwable $e) {
    $p->rollBack();
    throw $e;
}
blitz_show('job', $p->query('SELECT id, name, amount FROM pdo_dml WHERE name = \'job-1\'')->fetch(PDO::FETCH_ASSOC));

echo "== autocommit ==\n";
blitz_show('off', $p->setAttribute(PDO::ATTR_AUTOCOMMIT, false));
$p->exec("INSERT INTO pdo_dml (name, amount) VALUES ('ac', 1)");
blitz_show('in-tx', $p->inTransaction());
blitz_show('commit', $p->commit());
blitz_show('count', $p->query('SELECT COUNT(*) FROM pdo_dml')->fetchColumn());
blitz_show('on', $p->setAttribute(PDO::ATTR_AUTOCOMMIT, true));

$p->exec('DROP TABLE IF EXISTS pdo_dml');
