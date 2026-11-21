<?php

interface Named
{
    public string $name { get; }
}

interface Movable
{
    public string $position { get; set; }
}

class PlainNamed implements Named
{
    public string $name = "plain";

    public function __construct()
    {
        $this->name = "set";
    }
}

class VirtualNamed implements Named
{
    public string $name {
        get => "virtual";
    }
}

class SettableNamed implements Named, Movable
{
    public string $name {
        set {
            $this->name = $value;
            $this->storedName = $value;
        }
    }

    public string $storedName = "";

    public string $position = "0,0";
}

$plain = new PlainNamed();
echo $plain->name, "\n";
$virtual = new VirtualNamed();
echo $virtual->name, "\n";
$settable = new SettableNamed();
$settable->name = "written";
echo $settable->name, " ", $settable->storedName, " ", $settable->position, "\n";
$settable->position = "1,1";
echo $settable->position, "\n";

abstract class AbstractThing
{
    abstract public string $label { get; }

    public string $plain = "p";
}

class HookThing extends AbstractThing
{
    public string $label {
        get => "hook:" . $this->plain;
    }
}

class PlainThing extends AbstractThing
{
    public string $label = "plain-label";
}

echo (new HookThing())->label, "\n";
echo (new PlainThing())->label, "\n";

class ParentHooks
{
    public string $value {
        get => "P(" . $this->value . ")";
        set {
            $this->value = "p" . $value;
        }
    }
}

class ChildGet extends ParentHooks
{
    public string $value {
        get => "C(" . $this->value . ")";
    }
}

class ChildSet extends ParentHooks
{
    public string $value {
        set => "c" . $value;
    }
}

class ChildBoth extends ParentHooks
{
    public string $value {
        get => "[" . parent::$value::get() . "]";
        set {
            parent::$value::set($value);
            $this->value .= "!";
        }
    }
}

$childGet = new ChildGet();
$childGet->value = "x";
echo $childGet->value, "\n";

$childSet = new ChildSet();
$childSet->value = "x";
echo $childSet->value, "\n";

$childBoth = new ChildBoth();
$childBoth->value = "x";
echo $childBoth->value, "\n";

trait HasTitle
{
    public string $title {
        get => "T(" . $this->title . ")";
        set {
            $this->title = strtoupper($value);
        }
    }
}

class UsesTrait
{
    use HasTitle;
}

$uses = new UsesTrait();
$uses->title = "abc";
echo $uses->title, "\n";

class FinalGet
{
    public string $value {
        final get => "final:" . $this->value;
        set => $value;
    }
}

class FinalGetChild extends FinalGet
{
    public string $value {
        set {
            $this->value = "child:" . $value;
        }
    }
}

$final = new FinalGetChild();
$final->value = "v";
echo $final->value, "\n";
