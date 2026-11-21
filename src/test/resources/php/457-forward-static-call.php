<?php
enum Status: string
{
    case Active = 'active';
    case Inactive = 'inactive';
}

class Greeter
{
    public static function greet(string $name): string
    {
        return "Hello $name";
    }

    public static function run(): void
    {
        var_dump(forward_static_call('Status::from', 'active'));
        var_dump(forward_static_call_array('Status::from', ['inactive']));
        var_dump(forward_static_call([self::class, 'greet'], 'World'));
    }
}

Greeter::run();
