<?php
$who = "World";
$arr = ['k' => 'val', 0 => 'zero'];
$obj = new stdClass();
$obj->p = 'prop';
$sub = new stdClass();
$sub->q = 'deep';
$obj->sub = $sub;

// 1. Standard heredoc with interpolation & escapes
$h1 = <<<EOT
Hello $who!
arr=$arr[k] zero=$arr[0] prop=$obj->p
brace={$arr['k']} deep={$obj->sub->q}
escapes=\n\t\"literal\" \$x \x41 \u{2605}
EOT;
echo $h1, "\n---\n";

// 2. Quoted heredoc
$h2 = <<<"QUOTED"
quoted $who
QUOTED;
echo $h2, "\n---\n";

// 3. Nowdoc (no interpolation or escapes)
$h3 = <<<'RAW'
raw $who \n {$arr['k']}
RAW;
echo $h3, "\n---\n";

// 4. Flexible PHP 7.3+ indented heredoc/nowdoc with empty line and trailing code
$h4 = <<<IND
    line1

        line2 $who
    IND;
echo $h4, "\n---\n";

$h5 = <<<'INDRAW'
	tab1
		tab2 $who
	INDRAW;
echo $h5, "\n---\n";

// 5. Heredoc as function arg, array element, default param, class const
function take($a, $b = <<<DEF
    default val
    DEF) {
    return $a . '|' . $b;
}
class DocHolder {
    const C = <<<CLS
        class_const
        CLS;
}
$list = [
    <<<EL1
    first
    EL1,
    <<<'EL2'
    second
    EL2,
];
echo take(<<<ARG
    arg1
    ARG), "\n";
echo DocHolder::C, '|', implode(',', $list), "\n";

// 6. Deprecated ${var} / ${expr} interpolation (stdout only)
$s = "ab";
$name = "s";
echo "{$s}c|${s}c|${arr['k']}|${$name}\n";
