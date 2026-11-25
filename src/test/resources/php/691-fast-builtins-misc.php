<?php
namespace App;

const NS_CONST = 'ns';

class Thing {
    public const KIND = 'thing';
    public function __toString(): string {
        return 'thing-string';
    }
}

define('APP_CONST', 42);

function show(string $label, mixed $value): void {
    echo $label, ': ';
    var_dump($value);
}

// in_array, strict and loose, with the argument shapes the fast path answers and others.
$strings = ['one', 'two', 'three', 'four'];
$mixed = ['one', '2', 'three', '4', true, null, 0, 1.5];
$object = new Thing();
show('in-array-string', in_array('two', $strings));
show('in-array-string-missing', in_array('five', $strings));
show('in-array-strict', in_array('2', $strings, true));
show('in-array-strict-int', in_array(1, $mixed, true));
show('in-array-loose-int', in_array(2, $mixed));
show('in-array-loose-string', in_array('1', $mixed));
show('in-array-empty', in_array('x', []));
show('in-array-object-identity', in_array($object, [$object], true));
show('in-array-object-other', in_array(new Thing(), [$object], true));
show('in-array-objects-loose', in_array($object, [$object]));
show('in-array-array-needle', in_array(['a'], [['a'], ['b']], true));

// strtolower.
show('lower', strtolower('AbCdEf'));
show('lower-empty', strtolower(''));
show('lower-bytes', strtolower("caf\xC3\xA9 \xC3\x84BC"));
show('lower-int', strtolower(12345));

// explode with each limit shape.
show('explode', explode(',', 'a,b,c'));
show('explode-limit-1', explode(',', 'a,b,c', 1));
show('explode-limit-2', explode(',', 'a,b,c', 2));
show('explode-limit-0', explode(',', 'a,b,c', 0));
show('explode-limit-negative', explode(',', 'a,b,c', -1));
show('explode-limit-too-negative', explode(',', 'a,b,c', -5));
show('explode-limit-large', explode(',', 'a,b', 100));
show('explode-not-found', explode(',', 'abc'));
show('explode-trailing', explode(',', 'a,b,'));
show('explode-leading', explode(',', ',a,b'));
show('explode-empty-subject', explode(',', ''));
show('explode-multi-byte-separator', explode("\xC3\xA9", "a\xC3\xA9b"));
show('explode-multichar', explode('::', 'a::b::c'));

// implode of arrays whose values are strings or scalar conversions.
show('implode-list', implode(',', ['a', 'b', 'c']));
show('implode-assoc', implode('-', ['x' => 'a', 'y' => 'b']));
show('implode-empty', implode(',', []));
show('implode-one', implode(',', ['only']));
show('implode-glue-empty', implode('', ['a', 'b']));
show('implode-one-argument', implode(['a', 'b', 'c']));
show('implode-scalars', implode(',', [1, 2.5, true, false, null]));
show('implode-object', implode(',', [$object, 'x']));

// defined with a user constant, a builtin constant, a class constant and a miss.
show('defined-user', defined('APP_CONST'));
show('defined-namespaced', defined('App\\NS_CONST'));
show('defined-builtin', defined('PHP_VERSION'));
show('defined-class-const', defined('App\\Thing::KIND'));
show('defined-class-const-missing', defined('App\\Thing::NOPE'));
show('defined-missing', defined('APP_MISSING'));
show('defined-bytes', defined("\xC3\xA9"));

// extension_loaded is case-insensitive and finite.
show('extension-json', extension_loaded('json'));
show('extension-json-upper', extension_loaded('JSON'));
show('extension-mbstring', extension_loaded('mbstring'));
show('extension-missing', extension_loaded('nope'));
show('extension-bytes', extension_loaded("\xC3\xA9"));

// is_numeric.
foreach (['123', ' 123 ', '1e3', '1E-3', '0x1A', '12x', '', '  ', '+1.5', '.5', '5.',
          '1_000', 'INF', 'NAN', '4.5.6'] as $value) {
    show('numeric-' . var_export($value, true), is_numeric($value));
}
show('numeric-int', is_numeric(123));
show('numeric-float', is_numeric(1.5));
show('numeric-true', is_numeric(true));
show('numeric-null', is_numeric(null));
show('numeric-array', is_numeric([1]));

// array_key_last.
show('key-last-list', array_key_last([1, 2, 3]));
show('key-last-assoc', array_key_last(['a' => 1, 'b' => 2]));
show('key-last-empty', array_key_last([]));
$trimmed = [1, 2, 3];
unset($trimmed[2]);
show('key-last-after-unset', array_key_last($trimmed));
$allGone = ['a' => 1];
unset($allGone['a']);
show('key-last-empty-after-unset', array_key_last($allGone));
show('key-last-gap', array_key_last([5 => 'a', 2 => 'b']));
