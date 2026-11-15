<?php

$h = new Hoisted('early');
echo $h->msg, "\n";

var_dump(class_exists('Hoisted'), class_exists('Delayed'), class_exists('BlockLocal'));

require __DIR__ . '/inc/class_helper.php';

var_dump(class_exists('IncludedBase'), class_exists('Delayed'));

if (true) {
    class BlockLocal {
        public function hi(): string {
            return 'from-block';
        }
    }
}

$bl = new BlockLocal();
echo $bl->hi(), "\n";

class Delayed extends IncludedBase implements Describable {
    public function describe(): string {
        return Describable::KIND . ':' . $this->getTag();
    }
}

class Hoisted {
    public string $msg;
    public function __construct(string $msg) {
        $this->msg = $msg;
    }
}

$d = new Delayed();
echo $d->describe(), "\n";
var_dump($d instanceof IncludedBase, $d instanceof Describable);
