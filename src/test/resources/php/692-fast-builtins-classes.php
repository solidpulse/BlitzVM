<?php
namespace App;

interface Contract {
}

trait Shared {
}

class Base {
}

class Child extends Base implements Contract {
}

enum Suit: string {
    case Hearts = 'H';
}

function show(string $label, mixed $value): void {
    echo $label, ': ';
    var_dump($value);
}

$child = new Child();

// is_a with an object subject.
show('is-a-object-self', is_a($child, Child::class));
show('is-a-object-parent', is_a($child, Base::class));
show('is-a-object-interface', is_a($child, Contract::class));
show('is-a-object-no', is_a(new Base(), Child::class));
show('is-a-object-unknown', is_a($child, 'App\\Missing'));
show('is-a-object-allow-string-false', is_a($child, Contract::class, false));
show('is-a-enum', is_a(Suit::Hearts, Suit::class));
show('is-a-enum-unit', is_a(Suit::Hearts, \UnitEnum::class));

// is_a with a string subject, which needs $allow_string.
show('is-a-string', is_a('App\\Child', Contract::class, true));
show('is-a-string-no', is_a('App\\Base', Contract::class, true));
show('is-a-string-unknown', is_a('App\\Missing', Base::class, true));
show('is-a-string-leading-slash', is_a('\\App\\Child', Base::class, true));
show('is-a-string-case', is_a('app\\child', Base::class, true));

// class_exists on a class, an interface, a trait, an enum and a miss.
show('class-exists', class_exists(Child::class));
show('class-exists-base', class_exists(Base::class));
show('class-exists-interface', class_exists(Contract::class));
show('class-exists-trait', class_exists(Shared::class));
show('class-exists-enum', class_exists(Suit::class));
show('class-exists-missing', class_exists('App\\Missing'));
show('class-exists-missing-no-autoload', class_exists('App\\Missing', false));
show('class-exists-leading-slash', class_exists('\\App\\Child'));
show('class-exists-case', class_exists('app\\child'));
show('class-exists-no-autoload', class_exists(Child::class, false));

// interface_exists.
show('interface-exists', interface_exists(Contract::class));
show('interface-exists-class', interface_exists(Child::class));
show('interface-exists-missing', interface_exists('App\\Missing'));
show('interface-exists-missing-no-autoload', interface_exists('App\\Missing', false));
show('interface-exists-leading-slash', interface_exists('\\App\\Contract'));
show('interface-exists-case', interface_exists('app\\contract'));

// Fully qualified calls take the same path.
show('global-class-exists', \class_exists(Child::class));
show('global-interface-exists', \interface_exists(Contract::class));
show('global-is-a', \is_a($child, Base::class));
