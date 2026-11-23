<?php
// A user error handler is called for errors raised inside @; error_reporting() reports the
// silenced level there, so a handler can tell the error was silenced, as php does.
$seen = [];
set_error_handler(function (int $code, string $message) use (&$seen): bool {
    $level = error_reporting();
    $seen[] = [$code, $level, ($level & $code) === 0];
    echo "  handler $message code=$code er=$level\n";
    return true;
});

function raise_from_call(): void
{
    trigger_error('inside call', E_USER_NOTICE);
    echo "  call er=", error_reporting(), "\n";
}

echo "start er=", error_reporting(), "\n";
trigger_error('plain', E_USER_NOTICE);
echo "after plain er=", error_reporting(), "\n";
@trigger_error('silenced', E_USER_NOTICE);
echo "after silenced er=", error_reporting(), "\n";
@raise_from_call();
echo "after silenced call er=", error_reporting(), "\n";
@trigger_error('silenced deprecated', E_USER_DEPRECATED);
echo "after silenced deprecated er=", error_reporting(), "\n";
@is_a('stdClass', 'stdClass');
echo "after silenced is_a er=", error_reporting(), "\n";
@$undefinedForHandler;
echo "after silenced variable er=", error_reporting(), "\n";
$empty = [];
@$empty['key'];
echo "after silenced key er=", error_reporting(), "\n";

foreach ($seen as $i => [$code, $level, $silenced]) {
    echo "seen $i: code=$code er=$level silenced=", $silenced ? 'yes' : 'no', "\n";
}
