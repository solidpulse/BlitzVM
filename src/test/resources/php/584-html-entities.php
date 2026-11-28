<?php
// htmlspecialchars/htmlentities: every quote flag, double_encode, doctypes, invalid UTF-8 and
// non-ASCII text.
function show($label, $value) {
    echo $label, '=', bin2hex($value), "\n";
}

$specials = "a & b < c > d \" e ' f";
show('default', htmlspecialchars($specials));
show('noquotes', htmlspecialchars($specials, ENT_NOQUOTES));
show('compat', htmlspecialchars($specials, ENT_COMPAT));
show('quotes', htmlspecialchars($specials, ENT_QUOTES));
show('html5', htmlspecialchars($specials, ENT_QUOTES | ENT_HTML5));
show('xml1', htmlspecialchars($specials, ENT_QUOTES | ENT_XML1));
show('xhtml', htmlspecialchars($specials, ENT_QUOTES | ENT_XHTML));
show('double', htmlspecialchars('&amp; &lt; &#65; &bogus; &', ENT_QUOTES, 'UTF-8', true));
show('nodouble', htmlspecialchars('&amp; &lt; &#65; &bogus; &', ENT_QUOTES, 'UTF-8', false));
show('nodouble-html5', htmlspecialchars('&amp; &apos; &#x41; &bogus; &', ENT_QUOTES | ENT_HTML5, 'UTF-8', false));
show('plain', htmlspecialchars('no characters to escape here'));
show('empty', htmlspecialchars(''));
show('unicode', htmlspecialchars("caf\xC3\xA9 \xE2\x98\x83"));
show('invalid-none', htmlspecialchars("a\xFFb"));
show('invalid-substitute', htmlspecialchars("a\xFFb", ENT_QUOTES | ENT_SUBSTITUTE));
show('invalid-ignore', htmlspecialchars("a\xFFb", ENT_QUOTES | ENT_IGNORE));
show('disallowed-401', htmlspecialchars("a\x01b\x0Bc\x7Fd", ENT_QUOTES | ENT_SUBSTITUTE | ENT_DISALLOWED));
show('disallowed-html5', htmlspecialchars("a\x01b\x0Bc\x0Cd", ENT_QUOTES | ENT_SUBSTITUTE | ENT_HTML5 | ENT_DISALLOWED));
show('disallowed-xml', htmlspecialchars("a\x01b\x0Bc\x7Fd", ENT_QUOTES | ENT_SUBSTITUTE | ENT_XML1 | ENT_DISALLOWED));
show('latin1', htmlspecialchars("\xE9 & <", ENT_QUOTES, 'ISO-8859-1'));

// htmlentities adds the named entities.
show('entities', htmlentities("caf\xC3\xA9 & <b> \xE2\x82\xAC"));
show('entities-quotes', htmlentities("'\"&<>", ENT_QUOTES));
show('entities-html5', htmlentities("'\"&<>", ENT_QUOTES | ENT_HTML5));
show('entities-plain', htmlentities('plain ascii'));
show('entities-empty', htmlentities(''));
show('entities-named', htmlentities('&eacute; &amp; &copy;', ENT_QUOTES, 'UTF-8', false));
show('entities-multibyte', htmlentities("\xC3\x80\xC3\x89\xC3\x8E \xF0\x9F\x98\x80"));

// Decoding counterparts.
show('decode', html_entity_decode('&lt;a href=&quot;x&quot;&gt;&amp;&#039;&eacute;</a>'));
show('decode-special', htmlspecialchars_decode('&lt;&gt;&amp;&quot;&#039;&apos;'));
show('decode-special-compat', htmlspecialchars_decode('&lt;&gt;&amp;&quot;&#039;', ENT_COMPAT));
show('decode-plain', html_entity_decode('no entities here'));
