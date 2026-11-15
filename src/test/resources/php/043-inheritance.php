<?php

interface Named {
    public function name(): string;
}

class Animal implements Named {
    protected string $kind;
    private string $secret = 'animal-secret';

    public function __construct(string $kind) {
        $this->kind = $kind;
    }

    public function name(): string {
        return $this->kind;
    }

    public function speak(): string {
        return $this->kind . ':' . $this->sound();
    }

    public function sound(): string {
        return '...';
    }

    public function identify(): string {
        return self::label() . '/' . static::label();
    }

    public static function label(): string {
        return 'Animal';
    }

    public function animalSecret(): string {
        return $this->secret;
    }
}

class Dog extends Animal {
    private string $secret = 'dog-secret';
    public string $breed;

    public function __construct(string $kind, string $breed) {
        parent::__construct($kind);
        $this->breed = $breed;
    }

    public function sound(): string {
        return 'woof';
    }

    public function speakTwice(): string {
        return parent::speak() . '!';
    }

    public function callSelfSound(): string {
        return self::sound();
    }

    public function callParentSound(): string {
        return parent::sound();
    }

    public static function label(): string {
        return 'Dog';
    }

    public function dogSecret(): string {
        return $this->secret;
    }

    public function parentsHere(): array {
        return [get_class($this), get_parent_class($this), get_parent_class(self::class)];
    }
}

$dog = new Dog('canine', 'collie');
$animal = new Animal('beast');

echo $dog->name(), "\n";
echo $dog->speak(), "\n";
echo $dog->speakTwice(), "\n";
echo $dog->callSelfSound(), '|', $dog->callParentSound(), "\n";
echo $dog->identify(), "\n";
echo $dog->animalSecret(), '|', $dog->dogSecret(), "\n";
echo implode(',', $dog->parentsHere()), "\n";

var_dump(
    $dog instanceof Dog,
    $dog instanceof Animal,
    $dog instanceof Named,
    !($animal instanceof Dog),
    $dog instanceof UndeclaredClass
);

var_dump(
    get_parent_class($dog),
    get_parent_class('Dog'),
    get_parent_class($animal),
    is_a($dog, 'Animal'),
    is_a('Dog', 'Animal', true),
    is_a('Dog', 'Animal', false),
    is_subclass_of($dog, 'Animal'),
    is_subclass_of('Dog', 'Animal'),
    is_subclass_of($dog, 'Dog'),
    interface_exists('Named'),
    interface_exists('Animal')
);
