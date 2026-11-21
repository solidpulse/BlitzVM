<?php

interface Contract
{
    public string $required { get; set; }
}

class Sample implements Contract
{
    public string $plain = "p";
    public private(set) string $privateSet = "a";
    public protected(set) string $protectedSet = "b";
    public readonly string $readonly;
    public string $required = "r";

    public string $backed {
        get => $this->backed . "!";
        set => strtolower($value);
    }

    public string $virtual {
        get => "virtual";
    }

    public string $writeOnly {
        set => $value;
    }

    public string $typed {
        get => $this->typed;
        set => $value;
    }

    public function __construct()
    {
        $this->readonly = "ro";
    }
}

$sample = new Sample();
$sample->backed = "BOB";

foreach (["plain", "privateSet", "protectedSet", "readonly", "backed", "virtual", "writeOnly", "typed"] as $name) {
    $property = new ReflectionProperty(Sample::class, $name);
    echo $name,
        ": virtual=", (int) $property->isVirtual(),
        " hooks=", (int) $property->hasHooks(),
        " privateSet=", (int) $property->isPrivateSet(),
        " protectedSet=", (int) $property->isProtectedSet(),
        " mods=", $property->getModifiers();
    $settable = $property->getSettableType();
    echo " settable=", $settable === null ? "null" : (string) $settable, "\n";

    foreach ($property->getHooks() as $hookName => $method) {
        echo "  hook ", $hookName, ": ", $method->getName(),
            " params=", $method->getNumberOfParameters(),
            " required=", $method->getNumberOfRequiredParameters(),
            " mods=", $method->getModifiers(),
            " class=", $method->getDeclaringClass()->getName(), "\n";
        if ($method->getNumberOfParameters() > 0) {
            echo "    param=", $method->getParameters()[0]->getName(), "\n";
        }
    }

    $get = $property->getHook(PropertyHookType::Get);
    echo "  getHook=", $get === null ? "null" : $get->getName(), "\n";
    $set = $property->getHook(PropertyHookType::Set);
    echo "  setHook=", $set === null ? "null" : $set->getName(), "\n";
}

$backed = new ReflectionProperty(Sample::class, "backed");
echo "value=", $backed->getValue($sample), "\n";
echo "initialized=", (int) $backed->isInitialized($sample), "\n";
$backed->setValue($sample, "Sue");
echo "after=", $sample->backed, "\n";

$virtual = new ReflectionProperty(Sample::class, "virtual");
echo "virtual value=", $virtual->getValue($sample), " initialized=", (int) $virtual->isInitialized($sample), "\n";
try {
    $virtual->setValue($sample, "x");
} catch (Error $e) {
    echo "virtual set: ", $e->getMessage(), "\n";
}

$contract = new ReflectionProperty(Contract::class, "required");
foreach ($contract->getHooks() as $hookName => $method) {
    echo "contract ", $hookName, ": ", $method->getName(),
        " abstract=", (int) $method->isAbstract(),
        " class=", $method->getDeclaringClass()->getName(), "\n";
}

class ParentHooks
{
    public string $value {
        get => "p" . $this->value;
    }
}

class ChildHooks extends ParentHooks
{
    public string $value {
        set => $value;
    }
}

$inherited = new ReflectionProperty(ChildHooks::class, "value");
foreach ($inherited->getHooks() as $hookName => $method) {
    echo "inherited ", $hookName, ": ", $method->getName(),
        " class=", $method->getDeclaringClass()->getName(), "\n";
}
echo "inherited virtual=", (int) $inherited->isVirtual(), "\n";
