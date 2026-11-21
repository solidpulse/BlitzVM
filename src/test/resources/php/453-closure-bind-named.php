<?php
class Item
{
    private string $key = '';

    public function setKey(string $key): void
    {
        $this->key = $key;
    }

    public function key(): string
    {
        return $this->key;
    }
}

$factory = Closure::bind(
    closure: static function (string $key) {
        $item = new Item();
        $item->setKey($key);

        return $item;
    },
    newThis: null,
    newScope: Item::class,
);

var_dump($factory('abc')->key());

$other = Closure::bind(static fn (): string => 'bound', null, null);
var_dump($other());
