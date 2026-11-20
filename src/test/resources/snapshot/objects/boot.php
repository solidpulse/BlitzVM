<?php
class Item
{
    public $name;
    public $next;
    public $alias;

    public function __construct($name)
    {
        $this->name = $name;
    }
}

$app = new stdClass();
$app->name = 'app';
$app->first = new Item('one');
$app->second = new Item('two');
$app->shared = $app->first;
$app->first->next = $app->second;
$app->second->next = $app->first;
$app->first->alias = $app->second;
