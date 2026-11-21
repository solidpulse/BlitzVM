<?php
class Documented
{
    public int $plain;
    public array $items;
}

$plain = new ReflectionProperty(Documented::class, 'plain');
var_dump($plain->getDocComment());

$items = new ReflectionProperty(Documented::class, 'items');
var_dump($items->hasType(), (string) $items->getType());
