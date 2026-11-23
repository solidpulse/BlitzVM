<?php
// A lazy loop over a group, as tempest/highlight writes them. php's PCRE JIT runs out of
// stack on a large subject and reports PREG_JIT_STACKLIMIT_ERROR; a short subject matches.
$p = '/<s>((.|\n)*?)<\/s>/';
$small = '<s>' . str_repeat("a\nb", 100) . '</s>';
var_dump(preg_match($p, $small, $m), strlen($m[1]), preg_last_error_msg());

$big = '<s>' . str_repeat("a", 2000000) . '</s>';
$m = ['old'];
var_dump(preg_match($p, $big, $m), $m, preg_last_error(), preg_last_error() === PREG_JIT_STACKLIMIT_ERROR, preg_last_error_msg());
$m = ['old'];
var_dump(preg_match_all($p, "<s>x</s>$big", $m), $m, preg_last_error());
var_dump(preg_replace($p, 'R', $big), preg_last_error());
var_dump(preg_replace($p, 'R', ['k1' => '<s>x</s>', 'k2' => $big, 'k3' => '<s>y</s>']), preg_last_error());
$c = 0;
var_dump(preg_replace($p, 'R', "<s>x</s>$big", -1, $c), $c);
var_dump(preg_replace_callback($p, fn($m) => 'C', ['a' => '<s>x</s>', 'b' => $big]), preg_last_error());
var_dump(preg_replace_callback_array([$p => fn($m) => 'C'], '<s>q</s>' . $big), preg_last_error());
var_dump(preg_split($p, "a<s>x</s>b$big"), preg_last_error());
var_dump(preg_grep($p, ['<s>x</s>', $big, '<s>y</s>']), preg_last_error());
var_dump(preg_match('/a/', 'a'), preg_last_error(), preg_last_error_msg());
