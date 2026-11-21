<?php
require_once __DIR__ . '/boot.php';

echo 'property=', $card->suit === Suit::Hearts ? 'same' : 'different', "\n";
echo 'array value=', $card->all[0] === Suit::Hearts ? 'same' : 'different', "\n";
echo 'array other=', $card->all[1] === Suit::Spades ? 'same' : 'different', "\n";
echo 'from=', Suit::from('H') === Suit::Hearts ? 'same' : 'different', "\n";
echo 'from boot=', $from === Suit::Hearts ? 'same' : 'different', "\n";
echo 'cases=', count($cases), ' ', $cases[0] === Suit::Hearts ? 'same' : 'different', "\n";
echo 'match=', match (Suit::Hearts) {
    Suit::Hearts => 'hearts',
    Suit::Spades => 'spades',
}, "\n";
echo 'storage=', $card->storage[Suit::Hearts], ' ', $card->storage[Suit::Spades], "\n";
echo 'hearts id=', spl_object_id(Suit::Hearts) === $heartsId ? 'same' : 'different', "\n";
echo 'spades id=', spl_object_id(Suit::Spades) === $spadesId ? 'same' : 'different', "\n";
echo 'distinct=', spl_object_id(Suit::Hearts) !== spl_object_id(Suit::Spades) ? 'yes' : 'no', "\n";
echo 'name=', Suit::Hearts->name, ' ', Suit::Hearts->value, "\n";
