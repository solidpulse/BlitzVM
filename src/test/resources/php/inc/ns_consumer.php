<?php
namespace IncConsumer;

// Must NOT see IncLib's "use stdClass;" or IncLib namespace!
function check() {
    return __NAMESPACE__ . '|' . \IncLib\greet('world') . '|' . \IncLib\LIB_VER;
}
