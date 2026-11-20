<?php
// Typed properties: uninitialized reads, unset, references and static properties.
function strip($s) {
    return str_replace(__DIR__ . '/', '', $s);
}

class Typed {
    public int $number;
    public ?string $text;
    public static int $staticNumber;
    public static ?int $staticNullable = null;
    public static string $staticText = 'start';
}

$object = new Typed();
try { var_dump($object->number); } catch (Error $e) { echo strip($e->getMessage()), "\n"; }
try { var_dump($object->text); } catch (Error $e) { echo strip($e->getMessage()), "\n"; }
try { var_dump(Typed::$staticNumber); } catch (Error $e) { echo strip($e->getMessage()), "\n"; }
try { $reference = &$object->number; } catch (Error $e) { echo strip($e->getMessage()), "\n"; }
try { $reference = &Typed::$staticNumber; } catch (Error $e) { echo strip($e->getMessage()), "\n"; }

$object->number = '21';
var_dump($object->number);
unset($object->number);
try { var_dump($object->number); } catch (Error $e) { echo strip($e->getMessage()), "\n"; }
$object->number = 22;

$reference = &$object->number;
$reference = '23';
var_dump($object->number, $reference);

$other = 24;
$object->number = &$other;
$other = '25';
var_dump($object->number, $other);

$nullableReference = &Typed::$staticNullable;
var_dump($nullableReference, Typed::$staticNullable);
$nullableReference = '26';
var_dump(Typed::$staticNullable);

$initialized = 27;
Typed::$staticNumber = $initialized;
$staticReference = &Typed::$staticNumber;
$staticReference = '28';
var_dump(Typed::$staticNumber, $staticReference);

$bound = 29;
Typed::$staticNumber = &$bound;
$bound = '30';
var_dump(Typed::$staticNumber, $bound);

var_dump(Typed::$staticNullable);
Typed::$staticNullable = '30';
var_dump(Typed::$staticNullable);
Typed::$staticText = 31;
var_dump(Typed::$staticText);
