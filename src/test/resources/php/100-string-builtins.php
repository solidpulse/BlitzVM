<?php
// strspn / strcspn
var_dump(strspn("42 is the answer", "1234567890"), strspn("foo", "o", 1, 2), strspn("abc", ""), strspn("hello", "hel", -3));
var_dump(strcspn("abcd", "cd"), strcspn("hello", "l", 1), strcspn("abc", ""), strcspn("abcdhello", "l", -5, 2));

// substr_compare
var_dump(substr_compare("abcde", "bc", 1, 2), substr_compare("abcde", "de", -2), substr_compare("abcde", "bd", 1, 2),
    substr_compare("abcde", "BC", 1, 2, true), substr_compare("abcde", "BC", 1, 2), substr_compare("a", "abc", 0),
    substr_compare("abcdef", "ab", 0), substr_compare("abc", "xyz", 1, 0));

// strtr
var_dump(strtr("Hi all, I said hello", ["Hi" => "Hello", "hello" => "hi", "H" => "X"]));
var_dump(strtr("abc", "ab", "xyz"), strtr("abc", []), strtr("aaa", ["a" => "b", "aa" => "c"]), strtr("1 2 3", [1 => "one", 2 => "two"]));

// version_compare
foreach ([["5.2", "5.10"], ["1.0rc1", "1.0"], ["1.0", "1.0.0"], ["1.0-dev", "1.0alpha"], ["1.0pl1", "1.0"],
          ["", "1"], ["1.0.0", "1.0"], ["8.5.12", "7.4"], ["1.0a", "1.0b"], ["1.0RC2", "1.0rc1"], ["6.9-beta3", "6.9"],
          ["1.10", "1.9"], ["1..2", "1.2"], ["1_0", "1-0"], ["7.4.0", "7.4.0"], ["1.0#1", "1.0"]] as $p) {
    echo "$p[0] <=> $p[1]: ", version_compare($p[0], $p[1]), "\n";
}
var_dump(version_compare("8.5.12", "7.4", ">="), version_compare("1.0", "1.1", "lt"), version_compare("2", "2.0", "eq"), version_compare("2", "2", "ne"));

// slashes
var_dump(stripslashes("a\\\\b\\'c\\\"d"), addcslashes("foo[bar]", "A..Z"), addcslashes("zoo['.']", "a..z"));
var_dump(addcslashes("tab\there\nnew", "\0..\37"), stripcslashes('a\n\x41\101\q\\\\'), stripcslashes('\x4a\x4B\61'));

// natural order
var_dump(strnatcmp("img12", "img10"), strnatcmp("img2", "img10"), strnatcasecmp("A1", "a2"), strnatcmp("a01", "a1"),
    strnatcmp("x 1", "x1"), strnatcmp("", "a"), strnatcmp("abc", "abc"), strnatcasecmp("Hello", "hello"), strnatcmp("0.5", "0.25"));
$files = ["img12.png", "img10.png", "IMG2.png", "img1.png"];
echo implode(",", sort_nat($files)), "\n";
function sort_nat($a) {
    // insertion sort with strnatcasecmp (no closures or references yet)
    $n = count($a);
    for ($i = 1; $i < $n; $i++) {
        $v = $a[$i];
        $j = $i - 1;
        while ($j >= 0 && strnatcasecmp($a[$j], $v) > 0) {
            $a[$j + 1] = $a[$j];
            $j--;
        }
        $a[$j + 1] = $v;
    }
    return $a;
}

// strtok
$tok = strtok("This is\tan example\nstring", " \n\t");
while ($tok !== false) {
    echo "[$tok]";
    $tok = strtok(" \n\t");
}
echo "\n";
var_dump(strtok("/a//b/", "/"), strtok("/"), strtok("/"));

// substr_replace
var_dump(substr_replace("Hello World", "PHP", 6, 5), substr_replace("Hello", "J", 0, 1), substr_replace("Hello", "!!", -1, 0),
    substr_replace("Hello", "X", 2), substr_replace("Hello", "X", 10), substr_replace("Hello World", "", -5, -1));
print_r(substr_replace(["aaa", "bbb", "ccc"], "X", 1, 1));
print_r(substr_replace(["aaa", "bbb"], ["1", "2"], [0, 1], [1, 2]));

// stristr / strrchr / strpbrk
var_dump(stristr("USER@EXAMPLE.com", "e"), stristr("USER@EXAMPLE.com", "@", true), stristr("abc", "z"));
var_dump(strrchr("a/b/c", "/"), strrchr("a/b/c", "/x"), strrchr("abc", "z"), strpbrk("This is a test", "st"), strpbrk("abc", "z"));

// entities
var_dump(html_entity_decode("&apos;&#39;&eacute;&#233;&#xE9;&nbsp;&bogus;&amp;lt;&#0;&#x110000;&hellip;&copy;"));
var_dump(html_entity_decode("&#39;&quot;", ENT_COMPAT), html_entity_decode("&#39;&quot;", ENT_NOQUOTES), html_entity_decode("&apos;", ENT_QUOTES | ENT_HTML5));
var_dump(htmlspecialchars_decode("&#34; &#x22; &#39; &#039; &#x27; &apos; &quot; &amp;amp; &lt;b&gt; &eacute;"));
var_dump(htmlspecialchars_decode("&quot;&#039;", ENT_NOQUOTES), htmlspecialchars_decode("&quot;&#039;", ENT_COMPAT));

// chunk_split
var_dump(chunk_split("abcdefg", 3, "|"), chunk_split("abc", 5), chunk_split("", 2, "-"));

// uniqid
var_dump(strlen(uniqid()), strlen(uniqid("p_")), strlen(uniqid("", true)));
