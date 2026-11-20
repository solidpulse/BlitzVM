<?php
class A {}
class B {}
class ParentType {}
class ChildType extends ParentType {}
class Types extends ParentType {
    public ?int $nullable;
    public function m(?int $a, int|null $b, int|string|null $c, (A&B)|null $d, self $e, parent $f): static { return $this; }
}

function show($label, $type) {
    if ($type === null) {
        echo $label, "=null\n";
        return;
    }
    echo $label, " class=", get_class($type), " string=", (string)$type, " null=", (int)$type->allowsNull();
    if ($type instanceof ReflectionNamedType) {
        echo " name=", $type->getName(), " builtin=", (int)$type->isBuiltin();
    }
    echo "\n";
    if ($type instanceof ReflectionUnionType || $type instanceof ReflectionIntersectionType) {
        foreach ($type->getTypes() as $member) {
            echo "  member=", get_class($member), " string=", (string)$member;
            if ($member instanceof ReflectionNamedType) {
                echo " name=", $member->getName(), " builtin=", (int)$member->isBuiltin();
            }
            echo "\n";
        }
    }
}

$m = new ReflectionMethod('Types', 'm');
foreach ($m->getParameters() as $parameter) {
    show($parameter->getName(), $parameter->getType());
}
show('return', $m->getReturnType());
show('property', (new ReflectionProperty('Types', 'nullable'))->getType());
show('nullable-iterable', (new ReflectionFunction(function (?iterable $x) {}))->getParameters()[0]->getType());
show('mixed', (new ReflectionFunction(function (mixed $x) {}))->getParameters()[0]->getType());
show('none', (new ReflectionFunction(function ($x) {}))->getParameters()[0]->getType());
