<?php
namespace App;

interface Contract {
}

class Thing implements Contract {
}

function show(string $label, mixed $value): void {
    echo $label, ': ';
    var_dump($value);
}

$count = 0;
show('replace-named', str_replace(search: 'a', replace: 'b', subject: 'banana', count: $count));
show('replace-named-count', $count);
show('starts-named', str_starts_with(haystack: 'alpha', needle: 'al'));
show('ends-named', str_ends_with(haystack: 'alpha', needle: 'ha'));
show('contains-named', str_contains(haystack: 'alpha', needle: 'ph'));
show('trim-named', trim(string: '  x  ', characters: ' '));
show('ltrim-named', ltrim(string: 'xxabc', characters: 'x'));
show('rtrim-named', rtrim(string: 'abcxx', characters: 'x'));
show('strpos-named', strpos(haystack: 'banana', needle: 'na', offset: 3));
show('substr-named', substr(string: 'abcdef', offset: 1, length: 3));
show('substr-named-default', substr(string: 'abcdef', offset: 2));
show('in-array-named', in_array(needle: 'two', haystack: ['one', 'two'], strict: true));
show('strtolower-named', strtolower(string: 'ABC'));
show('explode-named', explode(separator: ',', string: 'a,b', limit: 1));
show('defined-named', defined(constant_name: 'PHP_VERSION'));
show('extension-named', extension_loaded(extension: 'json'));
show('numeric-named', is_numeric(value: '12'));
show('key-last-named', array_key_last(array: ['a' => 1, 'b' => 2]));
show('implode-named', implode(separator: '-', array: ['a', 'b']));
show('is-a-named', is_a(object_or_class: new Thing(), class: Contract::class));
show('is-a-named-string', is_a(object_or_class: 'App\\Thing', class: Contract::class, allow_string: true));
show('class-exists-named', class_exists(class: Thing::class, autoload: false));
show('interface-exists-named', interface_exists(interface: Contract::class, autoload: false));

// Named and positional arguments may be mixed, and a named argument for a later
// parameter may skip an optional one.
show('replace-mixed', str_replace('a', replace: 'b', subject: 'banana'));
show('trim-mixed', trim('  x  ', characters: ' '));
show('substr-mixed', substr('abcdef', offset: 1, length: 2));
