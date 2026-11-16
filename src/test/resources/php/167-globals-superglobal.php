<?php
$topVar = 'hello';
echo "read=" . $GLOBALS['topVar'] . "
";

function setDynamicGlobal($name, $val) {
    $GLOBALS[$name] = $val;
}
setDynamicGlobal('createdInFn', 123);
echo "dyn=$createdInFn," . $GLOBALS['createdInFn'] . "
";

$GLOBALS['nested'] = ['x' => 1];
$GLOBALS['nested']['x'] = 42;
echo "nested=" . $nested['x'] . "
";

unset($GLOBALS['createdInFn']);
echo "unset=" . (isset($createdInFn) ? 'yes' : 'no') . "," . (isset($GLOBALS['createdInFn']) ? 'yes' : 'no') . "
";

$keys = array_keys($GLOBALS);
echo "has_top=" . (in_array('topVar', $keys, true) ? '1' : '0')
    . ",has_GLOBALS=" . (in_array('GLOBALS', $keys, true) ? '1' : '0') . "
";

$foundTop = false;
foreach ($GLOBALS as $k => $v) {
    if ($k === 'topVar' && $v === 'hello') {
        $foundTop = true;
    }
}
echo "iter=" . ($foundTop ? 'ok' : 'fail') . "
";

echo "sg=" . (is_array($_GET) && is_array($_POST) && is_array($_SERVER) && is_array($_COOKIE)
    && is_array($_REQUEST) && is_array($_FILES) && is_array($_ENV) ? 'ok' : 'bad') . "
";
