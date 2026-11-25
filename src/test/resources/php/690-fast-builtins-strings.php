<?php
namespace App;

function show(string $label, mixed $value): void {
    echo $label, ': ';
    var_dump($value);
}

// str_replace with the plain string form and no $count.
show('replace', str_replace('a', 'b', 'banana'));
show('replace-empty-search', str_replace('', 'x', 'abc'));
show('replace-empty-replacement', str_replace('a', '', 'banana'));
show('replace-overlap', str_replace('ana', 'X', 'banana'));
show('replace-not-found', str_replace('z', 'q', 'banana'));
show('replace-empty-subject', str_replace('a', 'b', ''));
show('replace-bytes', str_replace("\xC3\xA9", 'e', "caf\xC3\xA9"));
show('replace-int-subject', str_replace('1', 'x', 123));

$count = 0;
$replaced = str_replace('a', 'b', 'banana', $count);
show('replace-count', [$replaced, $count]);
$count = 99;
str_replace(['a', 'n'], ['A', 'N'], 'banana', $count);
show('replace-count-array-search', $count);
show('replace-array-subject', str_replace('a', 'b', ['ana', 'nana']));

// str_starts_with / str_ends_with / str_contains.
show('starts', str_starts_with('alpha', 'al'));
show('starts-empty-needle', str_starts_with('alpha', ''));
show('starts-empty-subject', str_starts_with('', ''));
show('starts-no', str_starts_with('alpha', 'ph'));
show('starts-int', str_starts_with(12345, '123'));
show('starts-bytes', str_starts_with("\xC3\xA9t\xC3\xA9", "\xC3\xA9"));
show('ends', str_ends_with('alpha', 'ha'));
show('ends-empty-needle', str_ends_with('alpha', ''));
show('ends-no', str_ends_with('alpha', 'al'));
show('ends-bytes', str_ends_with("caf\xC3\xA9", "\xC3\xA9"));
show('contains', str_contains('alpha', 'ph'));
show('contains-empty-needle', str_contains('alpha', ''));
show('contains-no', str_contains('alpha', 'zz'));
show('contains-bytes', str_contains("caf\xC3\xA9", "\xC3\xA9"));

// trim, ltrim, rtrim with php's default character list.
show('trim', trim(" \t\n alpha \r\n"));
show('trim-plain', trim('alpha'));
show('trim-empty', trim(''));
show('trim-all', trim(" \t\n"));
show('ltrim', ltrim("  alpha  "));
show('rtrim', rtrim("  alpha  "));
show('trim-int', trim(123));
show('trim-bytes', trim(" \xC3\xA9 "));

// With an explicit character list and a range.
show('trim-chars', trim('xxabcxx', 'x'));
show('trim-range', trim('abcXYZcba', 'a..z'));
show('trim-chars-empty', trim('  abc  ', ''));
show('ltrim-chars', ltrim('xyxabc', 'xy'));
show('rtrim-chars', rtrim('abcxyx', 'xy'));
show('trim-bytes-chars', trim("\xC3\xA9\xC3\xA9abc\xC3\xA9", "\xC3\xA9"));

// strpos, including offsets php accepts.
show('strpos', strpos('banana', 'na'));
show('strpos-none', strpos('banana', 'zz'));
show('strpos-offset', strpos('banana', 'na', 3));
show('strpos-negative-offset', strpos('banana', 'na', -3));
show('strpos-empty-needle', strpos('banana', ''));
show('strpos-empty-needle-at-end', strpos('banana', '', 6));
show('strpos-empty-subject', strpos('', ''));
show('strpos-bytes', strpos("caf\xC3\xA9", "\xC3\xA9"));

// substr, including negative and out-of-range offsets.
show('substr', substr('abcdef', 1, 3));
show('substr-negative-start', substr('abcdef', -3));
show('substr-negative-length', substr('abcdef', 1, -2));
show('substr-past-end', substr('abcdef', 10));
show('substr-negative-past-start', substr('abcdef', -10));
show('substr-zero-length', substr('abcdef', 2, 0));
show('substr-big-length', substr('abcdef', 2, PHP_INT_MAX));
show('substr-empty', substr('', 0));
show('substr-bytes', substr("caf\xC3\xA9", 3));

// A namespace function shadows the builtin; calls through the global name do not.
function strtolower(string $value): string {
    return 'shadow:' . $value;
}
show('shadowed', strtolower('ABC'));
show('global', \strtolower('ABC'));
show('global-trim', \trim("  ok  "));
show('global-replace', \str_replace('a', 'b', 'banana'));
