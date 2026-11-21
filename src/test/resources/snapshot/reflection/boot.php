<?php
#[Attribute(Attribute::TARGET_ALL)]
class Mark
{
    public string $label;
    public array $extra;

    public function __construct(string $label = 'm', array $extra = [])
    {
        $this->label = $label;
        $this->extra = $extra;
    }
}

enum Tag: string
{
    case A = 'a';
    case B = 'b';
}

#[Mark('class', ['a' => 1])]
class Target
{
    #[Mark('const')]
    public const C = 41;

    #[Mark('prop')]
    public string $name = 'target';

    #[Mark('method')]
    public function greet(#[Mark('param')] string $who = 'world'): string
    {
        return 'hi ' . $who;
    }
}

class Holder
{
    public string $prefix = 'p:';
}

function plain($x)
{
    return $x * 2;
}

$target = new Target();
$tag = Tag::A;

$closureThis = new Holder();
$closure = Closure::bind(function (string $arg = 'a'): string {
    return $this->prefix . $arg;
}, $closureThis, Holder::class);

$shared = 7;
$refArray = ['x' => &$shared];

$refObject = new ReflectionObject($target);
$refClass = new ReflectionClass(Target::class);
$refMethod = new ReflectionMethod($target, 'greet');
$refProp = new ReflectionProperty(Target::class, 'name');
$refConst = new ReflectionClassConstant(Target::class, 'C');
$refFunction = new ReflectionFunction($closure);
$refParam = $refFunction->getParameters()[0];
$refPlain = new ReflectionFunction('plain');
$refPlainParam = $refPlain->getParameters()[0];
$refReturn = $refMethod->getReturnType();
$refAttr = (new ReflectionClass(Target::class))->getAttributes()[0];
$refParamAttr = $refMethod->getParameters()[0]->getAttributes()[0];
$refEnumCase = (new ReflectionEnum(Tag::class))->getCase('A');
$refReference = ReflectionReference::fromArrayElement($refArray, 'x');
