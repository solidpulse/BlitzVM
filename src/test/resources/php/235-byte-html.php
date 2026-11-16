<?php
$s = "é€😀<&\"'";
echo htmlspecialchars($s), "\n", htmlentities($s), "\n";
echo htmlspecialchars('&amp; &bogus; é', ENT_QUOTES, 'UTF-8', false), "\n";
foreach (["\xFF", "\xED\xA0\x80", "\xE2\x82", "a\xFF<&b"] as $s) {
    echo bin2hex(htmlspecialchars($s, ENT_QUOTES)), ':', bin2hex(htmlspecialchars($s, ENT_QUOTES | ENT_SUBSTITUTE)),
         ':', bin2hex(htmlspecialchars($s, ENT_QUOTES | ENT_IGNORE)), "\n";
}
echo bin2hex(html_entity_decode('&eacute;&euro;&#128512;&quot;&#039;')), "\n";
echo bin2hex(htmlspecialchars_decode('&quot;&#039;&amp;&eacute;')), "\n";
echo bin2hex(htmlspecialchars("\xE9<&", ENT_QUOTES, 'ISO-8859-1')), "\n";
echo bin2hex(html_entity_decode('&eacute;&euro;', ENT_QUOTES, 'ISO-8859-1')), "\n";
$table = get_html_translation_table(HTML_ENTITIES, ENT_QUOTES, 'UTF-8');
echo $table['é'], ':', $table['€'], ':', $table["'"], "\n";
