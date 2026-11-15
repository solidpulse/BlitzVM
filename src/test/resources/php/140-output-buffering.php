<?php
echo "start\n";
var_dump(ob_get_level(), ob_get_contents(), ob_get_length(), ob_get_status(), ob_list_handlers());

ob_start();
echo "one";
$inner = ob_get_contents();
$len = ob_get_length();
$clean = ob_get_clean();
var_dump($inner, $len, $clean, ob_get_level());

// nesting: inner flushes into outer
ob_start();
echo "outer-";
ob_start();
echo "inner-é";
var_dump(ob_get_level());
$st = ob_get_status(true);
ob_end_flush();
$outer = ob_get_clean();
var_dump($outer, count($st), $st[1]['level'], $st[1]['buffer_used'], $st[0]['name']);

// ob_clean and ob_flush
ob_start();
echo "discard";
ob_clean();
echo "keep";
ob_flush();
echo "-after";
$rest = ob_get_contents();
ob_end_clean();
echo "\n";
var_dump($rest);

// ob_get_flush returns and outputs
ob_start();
echo "gf";
$gf = ob_get_flush();
echo "\n";
var_dump($gf);

// failure cases return false
var_dump(ob_get_clean(), ob_get_contents());

// templates: capture a function's output
function render($name) {
    ob_start();
    ?>
<p>Hello <?= $name ?></p>
<?php
    return ob_get_clean();
}
$html = render('World');
var_dump($html);

// buffers left open at the end are flushed in order
ob_start();
echo "level1 ";
ob_start();
echo "level2\n";
