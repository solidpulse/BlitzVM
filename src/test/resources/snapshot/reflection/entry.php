<?php
require_once __DIR__ . '/boot.php';

echo 'class name=', $refObject->getName(), "\n";
echo 'class name same=', $refObject->getName() === $refClass->getName() ? 'yes' : 'no', "\n";
echo 'prop value=', $refObject->getProperty('name')->getValue($target), "\n";
$refObject->getProperty('name')->setValue($target, 'via reflection');
echo 'target identity=', $target->name, "\n";
echo 'method=', $refMethod->invoke($target, 'there'), "\n";
echo 'prop=', $refProp->getValue($target), "\n";
echo 'const=', $refConst->getValue(), "\n";
echo 'function=', $refFunction->invoke(), "\n";
echo 'closure same=', $refFunction->getClosure() === $closure ? 'yes' : 'no', "\n";
echo 'closure this=', $refFunction->getClosureThis() === $closureThis ? 'yes' : 'no', "\n";
echo 'param declaring=', $refParam->getDeclaringFunction()->getName(), "\n";
echo 'param declaring same=', $refParam->getDeclaringFunction() === $refFunction ? 'yes' : 'no', "\n";
echo 'param type=', $refParam->getType()?->getName() ?? 'none', "\n";
echo 'plain param declaring=', $refPlainParam->getDeclaringFunction()->getName(), "\n";
echo 'plain param declaring same=', $refPlainParam->getDeclaringFunction() === $refPlain ? 'yes' : 'no', "\n";
echo 'return type=', $refReturn->getName(), "\n";
echo 'attr=', $refAttr->getName(), ' ', implode(',', $refAttr->getArguments()), "\n";
echo 'param attr=', $refParamAttr->getName(), ' ', implode(',', $refParamAttr->getArguments()), "\n";
echo 'enum case=', $refEnumCase->getName(), ' ', $refEnumCase->getValue()->name, "\n";
echo 'enum same=', $refEnumCase->getValue() === $tag ? 'yes' : 'no', "\n";
echo 'reference same=', $refReference->getId() === ReflectionReference::fromArrayElement($refArray, 'x')->getId() ? 'yes' : 'no', "\n";
