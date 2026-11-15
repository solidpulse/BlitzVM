<?php
define('FROM_INC', 'value_from_inc');
echo "parent=" . PARENT_CONST . "\n";
echo inc_hoisted() . "\n";

function inc_hoisted() {
    return 'hoisted_in_inc:' . inc_helper();
}

function inc_helper() {
    return 'ok';
}
