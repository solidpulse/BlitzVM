<?php

class Person
{
    public string $name {
        get => strtoupper($this->name);
        set {
            $this->name = strtolower($value);
        }
    }

    public string $greeting {
        get => "Hello, " . $this->name;
    }

    public int $age = 30;

    public string $upper {
        get => strtoupper($this->name);
    }

    public function __clone()
    {
        $this->name = "cloned";
    }
}

$p = new Person();
$p->name = "Alice";
echo $p->name, "\n";
echo $p->greeting, "\n";
echo $p->upper, "\n";

var_dump($p);
print_r($p);
var_dump((array) $p);
var_dump(get_object_vars($p));
foreach ($p as $key => $value) {
    echo $key, "=", $value, "\n";
}

echo serialize($p), "\n";
$copy = clone $p;
echo $copy->name, " ", $p->name, "\n";

var_dump(isset($p->greeting));
var_dump(empty($p->upper));
var_dump($p->age);

class Short
{
    public string $text {
        get => "[" . $this->text . "]";
        set => $value . "!";
    }
}

$s = new Short();
$s->text = "hi";
echo $s->text, "\n";
var_dump($s);

class Promoted
{
    public function __construct(
        public string $title {
            get => strtoupper($this->title);
            set {
                $this->title = $value;
            }
        },
        public private(set) int $count = 0,
    ) {
    }
}

$pr = new Promoted("mixed");
$pr->title = "case";
echo $pr->title, " ", $pr->count, "\n";
var_dump($pr);
