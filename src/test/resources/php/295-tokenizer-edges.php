<?php
$cases = [
    "",
    "plain html only",
    "<?php",
    "<?php\n",
    "<?php ?>\n",
    "<?php ?>\n\nhtml",
    "<?php ?>\r\nhtml",
    "<?php ?>\r\n\r\nhtml",
    "<?php // comment with crlf\r\n# hash\r\n/* block\r\ncomment */\n/* unterminated",
    "<?php /** doc */ /**/ /***/ /**#@+ tpl */ /**#not doc */",
    "<?php 1..2; .5; 5.; 0x1f; 0b11; 0o17; 017; 1_000; 1e10; 1E-2;",
    "<?php \$a=1; \\$b=2;",
    "<?php \$a = \"\\\$x\"; \$b = \"\\{\$x}\"; \$c = 'a\\'b';",
    "<?php \$a = \"\$x[0][1]\"; \$b = \"\$x->y->z\"; \$c = \"{\$x[0]}\";",
    "<?php \$a = \"\${x}\"; \$b = \"\${\$x}\"; \$c = \"\${foo(1)}\";",
    "<?php \$a = <<<EOT\nEOT;\n",
    "<?php \$a = <<<EOT\n  text\n  EOT;\n",
    "<?php \$a = <<<EOT\n\$x {\$y} \$z\nEOT;\n",
    "<?php class A { public private(set) int \$x; }",
    "<?php \$a = \$b & \$c; \$d = &\$e; \$f = \$g & new H;",
    "<?php Foo\\Bar; \\Foo; namespace\\Foo; Foo\\;",
];
foreach ($cases as $src) {
    echo json_encode(token_get_all($src), JSON_UNESCAPED_SLASHES), "\n";
}
echo md5(json_encode(token_get_all(file_get_contents(__FILE__)))), "\n";
echo json_encode(token_get_all("<?php \$a = 1;", TOKEN_PARSE), JSON_UNESCAPED_SLASHES), "\n";
