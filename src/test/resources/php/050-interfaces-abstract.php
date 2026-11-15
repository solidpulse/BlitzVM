<?php

interface Printable {
    public const FORMAT = 'text';
    public function format(): string;
}

interface Identifiable {
    public const ID_PREFIX = 'id_';
    public function id(): int;
}

interface Entity extends Printable, Identifiable {
    public function label(): string;
}

abstract class BaseEntity implements Entity {
    protected int $id;

    public function __construct(int $id) {
        $this->id = $id;
    }

    public function id(): int {
        return $this->id;
    }

    abstract public function format(): string;
}

class PartialEntity implements Entity {
    public function label(): string {
        return 'partial';
    }
    public function id(): int {
        return 0;
    }
    public function format(): string {
        return 'ok';
    }
}

class ConcreteEntity extends BaseEntity {
    public function format(): string {
        return Identifiable::ID_PREFIX . $this->id . ':' . Printable::FORMAT;
    }

    public function label(): string {
        return 'concrete';
    }
}

$e = new ConcreteEntity(7);
$p = new PartialEntity();

echo $e->format(), '|', $e->label(), '|', $e->id(), "\n";
echo $p->label(), "\n";

var_dump(
    $e instanceof ConcreteEntity,
    $e instanceof BaseEntity,
    $e instanceof Entity,
    $e instanceof Printable,
    $e instanceof Identifiable,
    Entity::FORMAT,
    Entity::ID_PREFIX
);
