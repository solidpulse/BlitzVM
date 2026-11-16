<?php
function formatOrder($item, $qty = 1, $price = 10, $currency = 'USD') {
    return "$qty x $item @ $price $currency";
}

echo formatOrder(price: 25, item: 'Book') . "
";
echo formatOrder('Pen', currency: 'EUR') . "
";
echo formatOrder(...['currency' => 'GBP', 'item' => 'Notebook', 'qty' => 3]) . "
";

class Config {
    public $host;
    public $port;
    public $tls;
    public function __construct($host = 'localhost', $port = 80, $tls = false) {
        $this->host = $host;
        $this->port = $port;
        $this->tls = $tls;
    }
    public function describe($prefix = 'cfg', $verbose = false) {
        return "$prefix:{$this->host}:{$this->port}:" . ($this->tls ? 'tls' : 'plain') . ':' . ($verbose ? 'v' : 's');
    }
}

$cfg = new Config(tls: true, port: 443);
echo $cfg->describe(verbose: true) . "
";

function withVariadic($head, ...$extras) {
    return $head . '|' . json_encode($extras);
}
echo withVariadic('start', alpha: 1, beta: 2) . "
";

$clos = fn($a = 1, $b = 2, $c = 3) => "$a-$b-$c";
echo $clos(c: 30, a: 10) . "
";
