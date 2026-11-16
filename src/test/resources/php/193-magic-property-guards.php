<?php
// php's property guards: inside __get/__set/__isset/__unset for a property, accessing that
// same property on the same object does not call the magic method again.
class Bag {
    private $hidden = 'h';
    public function __get($n) { echo "get($n) "; return $this->$n ?? 'default'; }
    public function __set($n, $v) { echo "set($n) "; $this->$n = strtoupper($v); }
    public function __isset($n) { echo "isset($n) "; return isset($this->$n); }
    public function __unset($n) { echo "unset($n) "; unset($this->$n); }
}
$b = new Bag;
$b->color = 'red';            // __set creates the public property directly
echo "\n", $b->color, "\n";   // now a real property: no __get
var_dump(isset($b->size));     // __isset, inner isset sees no property
echo $b->size, "\n";           // __get, inner read is undefined (default via ??)
echo $b->hidden, "\n";         // private from outside: __get, inner read is allowed in scope
unset($b->hidden);             // __unset removes it
echo "\n";
var_dump(isset($b->hidden));
class Proxy {                   // wpdb-style __set that stores everything except a deny list
    public function __set($name, $value) {
        if (in_array($name, ['blocked'], true)) { return; }
        $this->$name = $value;
    }
}
$p = new Proxy; $p->a = 1; $p->blocked = 2; $p->a = 3;
var_dump($p);
