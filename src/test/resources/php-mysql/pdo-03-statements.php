<?php
require __DIR__ . '/pdo_connect.php';

class PdoRowClass {
    public $id;
    public $name;
    protected $grp = 'default-protected';
    private $n = 'default-private';
    public $extra = 'default-extra';
    public function __construct(public string $tag = 'none') {
        echo 'ctor(', $tag, ")\n";
    }
}

$p = blitz_pdo([PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$p->exec('DROP TABLE IF EXISTS pdo_stmt');
$p->exec('CREATE TABLE pdo_stmt (id INT AUTO_INCREMENT PRIMARY KEY, grp VARCHAR(5), name VARCHAR(20), n INT, f DOUBLE, d DECIMAL(8,2), body BLOB NULL, dt DATETIME NULL) ENGINE=InnoDB');
$p->exec("INSERT INTO pdo_stmt (grp, name, n, f, d, body, dt) VALUES ('g1','a',1,1.5,12.50,X'00FF','2024-01-01 10:00:00')");
$p->exec("INSERT INTO pdo_stmt (grp, name, n, f, d, body, dt) VALUES ('g1','b',-7,2.25,0.10,'bytes',NULL)");
$p->exec("INSERT INTO pdo_stmt (grp, name, n, f, d, body, dt) VALUES ('g2','c',NULL,NULL,NULL,NULL,NULL)");

echo "== prepare and query string ==\n";
$s = $p->prepare('SELECT id, name FROM pdo_stmt WHERE id = ?');
blitz_show('class', get_class($s));
blitz_show('queryString', $s->queryString);
blitz_show('columnCount-before', $s->columnCount());
blitz_show('getColumnMeta-before', $s->getColumnMeta(0));
blitz_show('rowCount-before', $s->rowCount());

echo "== execute forms ==\n";
blitz_show('execute-pos', $s->execute([1]));
blitz_show('fetch-pos', $s->fetch(PDO::FETCH_NUM));
blitz_show('rowCount-select', $s->rowCount());
blitz_show('columnCount', $s->columnCount());
$s = $p->prepare('SELECT id, name FROM pdo_stmt WHERE name = :name');
blitz_show('execute-named', $s->execute(['name' => 'b']));
blitz_show('fetch-named', $s->fetch(PDO::FETCH_ASSOC));
$s = $p->prepare('SELECT id, name FROM pdo_stmt WHERE name = :name');
$s->bindValue(':name', 'c');
blitz_show('bindValue-named', [$s->execute(), $s->fetchColumn(1)]);

echo "== bind types ==\n";
foreach ([[PDO::PARAM_STR, "a'b\\c\n"], [PDO::PARAM_INT, 42], [PDO::PARAM_INT, '42'],
          [PDO::PARAM_BOOL, true], [PDO::PARAM_BOOL, false], [PDO::PARAM_NULL, 'x'],
          [PDO::PARAM_LOB, "lob'x"], [PDO::PARAM_STR, 1.5]] as [$type, $value]) {
    $s = $p->prepare('SELECT ? AS v');
    $s->bindValue(1, $value, $type);
    $s->execute();
    blitz_show('bind-' . $type . '-' . gettype($value), $s->fetchColumn());
    $p->setAttribute(PDO::ATTR_EMULATE_PREPARES, false);
    $s = $p->prepare('SELECT ? AS v');
    $s->bindValue(1, $value, $type);
    $s->execute();
    blitz_show('native-' . $type . '-' . gettype($value), $s->fetchColumn());
    $p->setAttribute(PDO::ATTR_EMULATE_PREPARES, true);
}

echo "== bindParam by reference ==\n";
$value = 'one';
$s = $p->prepare('SELECT ? AS v');
$s->bindParam(1, $value, PDO::PARAM_STR);
$s->execute();
blitz_show('ref-one', $s->fetchColumn());
$value = 'two';
$s->execute();
blitz_show('ref-two', $s->fetchColumn());
$n = 5;
$s = $p->prepare('SELECT ? + 1 AS v');
$s->bindParam(1, $n, PDO::PARAM_INT);
$s->execute();
blitz_show('ref-int', $s->fetchColumn());

echo "== bindColumn ==\n";
$s = $p->query('SELECT name, n FROM pdo_stmt ORDER BY id');
$s->bindColumn('name', $boundName);
$s->bindColumn(2, $boundN, PDO::PARAM_STR);
while ($s->fetch(PDO::FETCH_BOUND)) {
    blitz_show('bound', [$boundName, $boundN]);
}

echo "== fetchObject ==\n";
$s = $p->query('SELECT id, name FROM pdo_stmt ORDER BY id');
blitz_show('std', get_object_vars($s->fetchObject()));
blitz_show('class', $s->fetchObject('PdoRowClass', ['obj']));
blitz_show('again', get_object_vars($s->fetchObject()));

echo "== getColumnMeta ==\n";
$s = $p->query('SELECT * FROM pdo_stmt');
foreach ([0, 1, 2, 3, 4, 5, 6, 7] as $i) {
    blitz_show('meta-' . $i, $s->getColumnMeta($i));
}
blitz_show('meta-missing', $s->getColumnMeta(99));
$s = $p->query("SELECT 1+1, COUNT(*) AS c, NOW() AS n");
blitz_show('meta-expr0', $s->getColumnMeta(0));
blitz_show('meta-expr1', $s->getColumnMeta(1));
blitz_show('meta-expr2', $s->getColumnMeta(2));
$p->setAttribute(PDO::ATTR_CASE, PDO::CASE_UPPER);
$s = $p->query('SELECT id AS Ident FROM pdo_stmt');
blitz_show('meta-case', $s->getColumnMeta(0));
$p->setAttribute(PDO::ATTR_CASE, PDO::CASE_NATURAL);

echo "== debugDumpParams ==\n";
$p->setAttribute(PDO::ATTR_EMULATE_PREPARES, true);
$s = $p->prepare('SELECT * FROM pdo_stmt WHERE name = :nm AND id > :i');
$s->bindValue(':nm', 'a');
$s->bindValue(':i', 0, PDO::PARAM_INT);
$s->execute();
ob_start();
$ret = $s->debugDumpParams();
blitz_show('dump', ob_get_clean());
blitz_show('dump-return', $ret);
$s = $p->prepare("SELECT ':x' AS a, ? AS b, 1 -- :comment\n, ? AS c");
$s->bindValue(1, 'p');
$s->bindValue(2, 'q', PDO::PARAM_INT);
$s->execute();
ob_start();
$s->debugDumpParams();
blitz_show('dump-quoted', ob_get_clean());
$p->setAttribute(PDO::ATTR_EMULATE_PREPARES, false);
$s = $p->prepare('SELECT * FROM pdo_stmt WHERE name = :nm');
ob_start();
$s->debugDumpParams();
blitz_show('dump-native', ob_get_clean());

echo "== statement misc ==\n";
$p->setAttribute(PDO::ATTR_EMULATE_PREPARES, true);
$s = $p->query('SELECT id, name FROM pdo_stmt ORDER BY id');
blitz_show('fetchColumn-name', $s->fetchColumn(1));
blitz_show('fetchColumn-num', $s->fetchColumn(0));
blitz_show('fetch', $s->fetch(PDO::FETCH_NUM));
blitz_show('closeCursor', $s->closeCursor());
blitz_show('fetch-after-close', $s->fetch());
blitz_show('closeCursor-again', $s->closeCursor());
blitz_show('nextRowset', $s->nextRowset());
blitz_show('error', [$s->errorCode(), $s->errorInfo()]);
blitz_show('execute-again', $s->execute());
blitz_show('fetch-again', $s->fetch(PDO::FETCH_ASSOC));

echo "== setFetchMode ==\n";
$s = $p->query('SELECT id, name, grp, n FROM pdo_stmt ORDER BY id');
blitz_show('mode', $s->setFetchMode(PDO::FETCH_CLASS, 'PdoRowClass', ['mode']));
blitz_show('fetch', $s->fetch());
blitz_show('fetch2', $s->fetch());
$s = $p->query('SELECT id, name FROM pdo_stmt ORDER BY id');
$s->setFetchMode(PDO::FETCH_ASSOC);
blitz_show('assoc', $s->fetchAll());

echo "== foreach ==\n";
$s = $p->query('SELECT id, name FROM pdo_stmt ORDER BY id');
foreach ($s as $key => $row) {
    blitz_show('iter', [$key, $row]);
}
blitz_show('after', $s->fetch());
$s->execute();
foreach ($s as $key => $row) {
    blitz_show('iter2', [$key, $row]);
}
$it = $s->getIterator();
blitz_show('iterator-class', get_class($it));
blitz_show('iterator-rewind', $it->rewind());
blitz_show('iterator-valid', $it->valid());
blitz_show('iterator-current', $it->current());
blitz_show('iterator-key', $it->key());
blitz_show('iterator-next', $it->next());
blitz_show('iterator-current2', $it->current());

$p->exec('DROP TABLE IF EXISTS pdo_stmt');
