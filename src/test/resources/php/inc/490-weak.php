<?php
function weak_calls() {
    $c = new TypedTarget();
    var_dump(takes_float(5), takes_union("5"), takes_union(5.5), takes_int("42"), takes_string(12));
    var_dump(takes_bool_or_int(true), takes_nullable_obj(null));
    try { takes_int("abc"); } catch (TypeError $e) { echo preg_replace('/called in .*on line/', 'called in FILE on line', $e->getMessage()), "\n"; }
    var_dump($c->withSelf($c) === $c, $c->unionOfClasses(new ArrayObject([])) instanceof Countable);
}
