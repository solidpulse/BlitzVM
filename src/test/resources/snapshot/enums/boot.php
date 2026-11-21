<?php
enum Suit: string
{
    case Hearts = 'H';
    case Spades = 'S';
}

class Card
{
    public ?Suit $suit = null;
    public array $all = [];
    public $storage;
}

$card = new Card();
$card->suit = Suit::Hearts;
$card->all = ['first' => Suit::Hearts, Suit::Spades];
$card->storage = new SplObjectStorage();
$card->storage[Suit::Hearts] = 'hearts';
$card->storage[Suit::Spades] = 'spades';

$heartsId = spl_object_id(Suit::Hearts);
$spadesId = spl_object_id(Suit::Spades);
$from = Suit::from('H');
$cases = Suit::cases();
