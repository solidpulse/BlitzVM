<?php
require_once __DIR__ . '/boot.php';

echo 'tag=', $post->tag?->name ?? 'null', "\n";
echo 'same=', $post->tag === BootTag0::A ? 'yes' : 'no', "\n";
echo 'from=', BootTag0::from('a') === BootTag0::A ? 'yes' : 'no', "\n";
echo 'unit=', is_a(BootTag0::A, UnitEnum::class, true) ? 'yes' : 'no', "\n";
echo 'backed=', is_a(BootTag0::A, BackedEnum::class, true) ? 'yes' : 'no', "\n";
echo 'storage=', $post->storage[BootTag0::A], "\n";
