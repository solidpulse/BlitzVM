<?php
enum Suit {
    case Hearts;
    case Spades;
}
enum Status: string {
    case Active = 'active';
    case Off = 'off';
}
enum Level: int {
    case Low = 1;
    case High = 10;
}

set_error_handler(function ($no, $msg) {
    echo "ERR[$no]: $msg\n";
    return true;
});

echo serialize(Suit::Hearts), "\n";
echo serialize(Status::Active), "\n";
echo serialize(Level::High), "\n";
echo serialize([Suit::Hearts, Suit::Hearts, Status::Off, Level::Low]), "\n";
var_dump(Suit::Hearts);
var_dump(Status::Off);
var_dump(Level::High);
echo Suit::Hearts->name, ' ', Status::Off->name, ' ', Status::Off->value, ' ', Level::High->value, "\n";

$back = unserialize('E:11:"Suit:Hearts";');
var_dump($back);
var_dump($back === Suit::Hearts);
$backStatus = unserialize('E:10:"Status:Off";');
var_dump($backStatus);
var_dump($backStatus === Status::Off);
$backLevel = unserialize('E:10:"Level:High";');
var_dump($backLevel);
var_dump($backLevel === Level::High);

$default = unserialize('E:11:"Suit:Hearts";', ['allowed_classes' => false]);
var_dump($default);
var_dump($default === Suit::Hearts);

var_dump(unserialize('E:11:"Suit:Missing";'));
var_dump(unserialize('E:11:"Status:Gone";'));
var_dump(unserialize('E:10:"Level:Gone";'));
var_dump(unserialize('E:8:"Suit:Hearts";'));
var_dump(unserialize('E:12:"Suit:Hearts";'));
var_dump(unserialize('E:7:"Missing";'));
var_dump(unserialize('E:13:"NoSuch:Hearts";'));
var_dump(unserialize('E:10:"stdClass:X";'));

try {
    unserialize('O:4:"Suit":0:{}');
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    unserialize('O:6:"Status":0:{}');
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    var_dump(unserialize('C:4:"Suit":0:{}'));
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    var_dump(unserialize('C:6:"Status":0:{}'));
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
