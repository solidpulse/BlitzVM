<?php
require __DIR__ . '/pdo_connect.php';

class PdoFetchRow {
    public $id;
    public $name;
    protected $grp = 'prot';
    private $n = 'priv';
    public $extra = 'extra';
    public function __construct(public string $tag = 'none') {
        echo 'ctor(', $tag, ")\n";
    }
}

class PdoIntoProt {
    protected $grp = 'prot';
}

$p = blitz_pdo([PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$p->exec('DROP TABLE IF EXISTS pdo_fetch');
$p->exec('CREATE TABLE pdo_fetch (id INT PRIMARY KEY, grp VARCHAR(5), name VARCHAR(10), n INT, f DOUBLE, d DECIMAL(6,2))');
$p->exec("INSERT INTO pdo_fetch VALUES (1,'g1','a',1,1.5,12.50),(2,'g1','b',2,2.5,0.10),(3,'g2','c',NULL,NULL,NULL)");

foreach ([PDO::FETCH_ASSOC => 'assoc', PDO::FETCH_NUM => 'num', PDO::FETCH_BOTH => 'both',
          PDO::FETCH_OBJ => 'obj', PDO::FETCH_NAMED => 'named'] as $mode => $label) {
    $rows = $p->query('SELECT id, grp, name, n FROM pdo_fetch ORDER BY id')->fetchAll($mode);
    if ($mode === PDO::FETCH_OBJ) {
        $rows = array_map(fn($o) => get_object_vars($o), $rows);
    }
    blitz_show($label, $rows);
}

echo "== lazy ==\n";
$s = $p->query('SELECT id, name, n FROM pdo_fetch ORDER BY id');
$row = $s->fetch(PDO::FETCH_LAZY);
blitz_show('class', get_class($row));
blitz_show('values', [$row->queryString, $row->id, $row->name, $row->n]);
blitz_show('isset', [isset($row->id), isset($row->nope)]);
$row2 = $s->fetch(PDO::FETCH_LAZY);
blitz_show('second', [$row2->id, $row2->name]);
$row3 = $s->fetch(PDO::FETCH_LAZY);
blitz_show('end', [$row3->queryString, $row3->id, $row3->name]);
blitz_show('end2', $s->fetch(PDO::FETCH_LAZY));

echo "== column and key pair ==\n";
blitz_show('column-default', $p->query('SELECT id, name FROM pdo_fetch ORDER BY id')->fetchAll(PDO::FETCH_COLUMN));
blitz_show('column-1', $p->query('SELECT id, name FROM pdo_fetch ORDER BY id')->fetchAll(PDO::FETCH_COLUMN, 1));
blitz_show('key-pair', $p->query('SELECT name, id FROM pdo_fetch ORDER BY id')->fetchAll(PDO::FETCH_KEY_PAIR));
blitz_show('key-pair-empty', $p->query('SELECT id FROM pdo_fetch WHERE id = 99')->fetchAll(PDO::FETCH_KEY_PAIR));
blitz_error('key-pair-wrong', fn() => $p->query('SELECT id FROM pdo_fetch')->fetchAll(PDO::FETCH_KEY_PAIR));

echo "== group and unique ==\n";
blitz_show('group-assoc', $p->query('SELECT grp, id, name FROM pdo_fetch ORDER BY id')->fetchAll(PDO::FETCH_GROUP | PDO::FETCH_ASSOC));
blitz_show('group-num', $p->query('SELECT grp, id, name FROM pdo_fetch ORDER BY id')->fetchAll(PDO::FETCH_GROUP | PDO::FETCH_NUM));
blitz_show('group-both', $p->query('SELECT grp, name FROM pdo_fetch ORDER BY id')->fetchAll(PDO::FETCH_GROUP | PDO::FETCH_BOTH));
blitz_show('group-column', $p->query('SELECT grp, name FROM pdo_fetch ORDER BY id')->fetchAll(PDO::FETCH_GROUP | PDO::FETCH_COLUMN));
blitz_show('group-obj', array_map(fn($rows) => array_map(fn($o) => get_object_vars($o), $rows),
    $p->query('SELECT grp, name FROM pdo_fetch ORDER BY id')->fetchAll(PDO::FETCH_GROUP | PDO::FETCH_OBJ)));
blitz_show('unique-both', $p->query('SELECT grp, name FROM pdo_fetch ORDER BY id')->fetchAll(PDO::FETCH_UNIQUE));
blitz_show('unique-assoc', $p->query('SELECT grp, name FROM pdo_fetch ORDER BY id')->fetchAll(PDO::FETCH_UNIQUE | PDO::FETCH_ASSOC));
blitz_show('unique-num', $p->query('SELECT grp, name FROM pdo_fetch ORDER BY id')->fetchAll(PDO::FETCH_UNIQUE | PDO::FETCH_NUM));

echo "== fetch class ==\n";
blitz_show('fetch-class', $p->query('SELECT id, name, grp, n FROM pdo_fetch ORDER BY id')
    ->fetchAll(PDO::FETCH_CLASS, 'PdoFetchRow', ['early']));
blitz_show('fetch-class-late', $p->query('SELECT id, name, grp, n FROM pdo_fetch ORDER BY id')
    ->fetchAll(PDO::FETCH_CLASS | PDO::FETCH_PROPS_LATE, 'PdoFetchRow', ['late']));
$s = $p->query('SELECT id, name FROM pdo_fetch ORDER BY id');
blitz_show('fetchObject', $s->fetchObject('PdoFetchRow', ['object']));
blitz_show('fetchObject-std', get_object_vars($s->fetchObject()));
blitz_error('fetch-class-missing', fn() => $p->query('SELECT id FROM pdo_fetch')->fetchAll(PDO::FETCH_CLASS, 'NoSuchRowClass'));
blitz_error('fetch-class-none', fn() => $p->query('SELECT id FROM pdo_fetch')->fetch(PDO::FETCH_CLASS));

echo "== fetch into ==\n";
$target = new PdoIntoProt();
$s = $p->query('SELECT grp FROM pdo_fetch');
$s->setFetchMode(PDO::FETCH_INTO, $target);
blitz_error('into-protected', fn() => $s->fetch());
$plain = new stdClass();
$s = $p->query('SELECT id, name FROM pdo_fetch ORDER BY id');
$s->setFetchMode(PDO::FETCH_INTO, $plain);
blitz_show('into', get_object_vars($s->fetch()));
blitz_error('into-fetchall', fn() => $p->query('SELECT id FROM pdo_fetch')->fetchAll(PDO::FETCH_INTO, $plain));

echo "== fetch args ==\n";
$s = $p->query('SELECT id, name FROM pdo_fetch ORDER BY id');
blitz_show('assoc', $s->fetch(PDO::FETCH_ASSOC));
blitz_show('column', $s->fetch(PDO::FETCH_COLUMN, 0));
blitz_show('default', $s->fetch());
blitz_error('bad-mode', fn() => $s->fetch(999));

$p->exec('DROP TABLE IF EXISTS pdo_fetch');
