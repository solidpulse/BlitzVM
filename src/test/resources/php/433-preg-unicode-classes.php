<?php
// /u: ASCII and UTF-8 subjects, invalid UTF-8, byte offsets, Unicode classes,
// case folding and \b with non-ASCII letters.

function show($label, $value) {
    echo $label, ': ', var_export($value, true), ' err=', preg_last_error(), "\n";
}

// ASCII subject under /u: no conversion is needed, offsets are still byte offsets.
show('ascii match', preg_match('/(?<letter>b)(c)/u', 'abc', $m));
show('ascii value', $m);
show('ascii offsets', preg_match('/(b)(c)/u', 'abc', $m, PREG_OFFSET_CAPTURE));
show('ascii offsets value', $m);

// Valid UTF-8: offsets count bytes, captures are whole characters.
show('utf8 match', preg_match('/(é)(x)/u', 'aéx', $m, PREG_OFFSET_CAPTURE));
show('utf8 value', $m);
show('utf8 all', preg_match_all('/./u', 'a😀b', $m, PREG_OFFSET_CAPTURE));
show('utf8 all value', $m);
show('utf8 empty matches', preg_match_all('/x*/u', '😀a', $m, PREG_OFFSET_CAPTURE));
show('utf8 empty value', $m);
show('utf8 offset inside char', preg_match('/b/u', 'ééb', $m, 0, 1));
show('utf8 offset at char', preg_match('/b/u', 'ééb', $m, 0, 2));

// \w, \d, \s, \b under /u use Unicode tables (PCRE2_UCP).
show('w eacute', preg_match('/^\w+$/u', 'café'));
show('w cyrillic', preg_match('/^\w+$/u', 'Привет'));
show('w ascii', preg_match('/^\w+$/u', 'cafe'));
show('d arabic', preg_match('/^\d+$/u', '١٢٣'));
show('d ascii', preg_match('/^\d+$/u', '123'));
show('s nbsp', preg_match('/^\s+$/u', "\u{00A0}"));
show('s ascii', preg_match('/^\s+$/u', " \t\n"));
show('b eacute', preg_match_all('/\b/u', 'café', $m));
show('b value', $m);
show('b words', preg_match_all('/\b\w+\b/u', 'un café noir', $m, PREG_OFFSET_CAPTURE));
show('b words value', $m);

// POSIX classes under /u.
show('alpha', preg_match('/^[[:alpha:]]+$/u', 'café'));
show('alpha ascii', preg_match('/^[[:alpha:]]+$/u', 'cafe'));
show('alnum', preg_match('/^[[:alnum:]]+$/u', 'café123'));
show('space', preg_match('/^[[:space:]]+$/u', "\u{00A0} "));
show('word', preg_match('/^[[:word:]]+$/u', 'café'));
show('punct', preg_match('/^[[:punct:]]+$/u', '.,!?'));
show('digit', preg_match('/^[[:digit:]]+$/u', '١٢٣'));

// Case-insensitive matching uses Unicode case folding under /u.
show('ci eacute', preg_match('/^é$/iu', 'É'));
show('ci eacute upper', preg_match('/^É$/iu', 'é'));
show('ci greek', preg_match('/^σ$/iu', 'Σ'));
show('ci cyrillic', preg_match('/^привет$/iu', 'ПРИВЕТ'));
show('ci class', preg_match('/^[a-zé]+$/iu', 'CAFÉ'));
show('ci property', preg_match('/^\p{Lu}+$/u', 'ÉCOLE'));

// Named properties still work.
show('property L', preg_match('/^\p{L}+$/u', 'café'));
show('property N', preg_match('/^\p{N}+$/u', '١٢٣'));
show('property Lu', preg_match('/^\p{Lu}+$/u', 'É'));

// Invalid UTF-8 subjects: false and PREG_BAD_UTF8_ERROR (4).
$bad = "a\xffb";
show('invalid match', preg_match('/a/u', $bad, $m));
show('invalid all', preg_match_all('/a/u', $bad, $m));
show('invalid replace', preg_replace('/a/u', 'x', $bad));
show('invalid split', preg_split('/a/u', $bad));
show('invalid no match first', preg_match('/zzz/u', $bad));

// preg_last_error_msg names the error.
echo 'msg=', preg_last_error_msg(), "\n";

// A valid UTF-8 pattern on an invalid subject recovers on the next call.
show('recovered', preg_match('/a/u', 'abc'));
show('recovered err', preg_last_error());
