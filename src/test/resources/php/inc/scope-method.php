<?php
echo $this->secret, ':', self::LABEL, ':', static::LABEL, ':', $arg, "\n";
$this->secret .= '!';
$made = function () use ($arg) { return $this->secret . ':' . self::LABEL . ':' . $arg; };
return 12;
