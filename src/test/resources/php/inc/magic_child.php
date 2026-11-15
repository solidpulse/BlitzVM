<?php
echo basename(__FILE__), ":", basename(__DIR__), ":", __LINE__, "\n";
var_dump(dirname(__FILE__) === __DIR__);
function childMagic() {
    var_dump(__FUNCTION__, __LINE__);
}
childMagic();
