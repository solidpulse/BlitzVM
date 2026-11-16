<?php
namespace App\Ex {
    class CustomEx extends \Exception {}
}

namespace App {
    require __DIR__ . '/inc/ns_lib.php';
    require __DIR__ . '/inc/ns_consumer.php';

    use IncLib\Box;
    use IncLib\Box as LibBox;
    use IncLib\{
        function greet,
        const LIB_VER as VER,
    };
    use IncLib;
    use App\Ex\CustomEx;

    $b1 = new Box(10);
    $b2 = new LibBox(20);
    echo $b1->v, '|', $b2->v, '|', $b1->typeOf(), "\n";
    echo greet('Alice'), '|', IncLib\greet('Bob'), '|', VER, "\n";
    echo \IncConsumer\check(), "\n";
    var_dump($b1 instanceof Box, $b1 instanceof \IncLib\Box, $b1 instanceof LibBox);

    try {
        throw new CustomEx('boom');
    } catch (CustomEx | \LogicException $e) {
        echo 'caught:', get_class($e), ':', $e->getMessage(), "\n";
    }
}
