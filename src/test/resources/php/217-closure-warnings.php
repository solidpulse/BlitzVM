<?php
set_error_handler(function($errno, $message) { echo $message, "\n"; return true; });
class WarningScope { private $v = 1; public function closure() { return function() { return $this->v; }; } }
$static = static function () { return 1; };
var_dump($static->bindTo(new WarningScope));
$c = (new WarningScope)->closure();
var_dump($c->bindTo(null));
$plain = function () { return 1; };
var_dump($plain->bindTo(null, stdClass::class));
var_dump($plain->bindTo(null, 'MissingScope'));
