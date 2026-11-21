<?php
set_error_handler(function ($no, $msg) {
    echo "ERR[$no]: $msg\n";
    return true;
});

function check($data) {
    $result = unserialize($data);
    if (is_object($result)) {
        echo get_class($result), ' ', serialize($result), "\n";
    } else {
        var_dump($result);
    }
}

check('');
check(' ');
check('N');
check('N;');
check('x');
check('i:5');
check('i:foo;');
check('i:+5;');
check('b:2;');
check('d:foo;');
check('s:3');
check('s:3:x";');
check('s:5:"abc";');
check('s:3:"abc"x');
check('s:-1:"";');
check('s:99999999999999999999:"";');
check('a:1:i:0;');
check('a:1:{i:0;i:1;');
check('a:1:{d:1.5;i:1;}');
check('a:1:{i:0;i:1;i:1;i:2;}');
check('a:-1:{}');
check('a:x:{}');
check('a:999999999999999999999:{}');
check('O:1:"A":0:{}');
check('O:5:"A');
check('O:2:"A":0:{}');
check('O:-1:"A":0:{}');
check('O:1:"\\":0:{}');
check("O:1:\"\0\":0:{}");
check('O:1:"A":0:');
check('O:1:"A":x:{}');
check('O:1:"A":-1:{}');
check('O:1:"A":999999999999999999999:{}');
check('O:1:"A":1:{s:1:"x";i:1}');
check('O:1:"A":1:{i:0;i:1;}');
check('O:1:"A":2:{s:1:"x";i:1;}');
check('O:1:"A":0:{}}');
check('C:1:"A":3:{abc}');
check('C:1:"A":3:abc');
check('C:1:"A":5:{abc}');
check('C:1:"A":1:{abc}');
check('C:1:"A":-1:{abc}');
check('C:8:"stdClass":3:{abc}');
check('E:4:"Suit";');
check('E:0:"";');
check('E:11:"Suit:Hearts"');
check('E:11:"Suit:Hearts"x');
check('E:15:"Suit:Hearts";');
check('E:5:"Suit:Hearts";');
check('r:1;');
check('r:0;');
check('r:2;');
check('R:1;');
check('R:0;');
check('a:2:{i:0;s:1:"x";i:1;R:2;}');
check('a:2:{i:0;O:8:"stdClass":0:{}i:1;R:3;}');
