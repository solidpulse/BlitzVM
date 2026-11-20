<?php
function describeFunction(ReflectionFunction $function): void {
    echo $function->getName(), ' internal=', (int)$function->isInternal(), ' user=', (int)$function->isUserDefined(),
        ' closure=', (int)$function->isClosure(),
        ' parameters=', $function->getNumberOfParameters(), ' required=', $function->getNumberOfRequiredParameters(),
        ' file=', var_export($function->getFileName(), true),
        ' start=', var_export($function->getStartLine(), true),
        " names=";
    foreach ($function->getParameters() as $parameter) {
        echo $parameter->getName(), ',';
    }
    echo "\n";
}

describeFunction(new ReflectionFunction('strlen'));
describeFunction(new ReflectionFunction('array_map'));
describeFunction(new ReflectionFunction('str_repeat'));
echo 'strlen invoke=', (new ReflectionFunction('strlen'))->invoke('hello'), "\n";
echo 'str_repeat invoke=', (new ReflectionFunction('str_repeat'))->invoke('ab', 3), "\n";

$std = new ReflectionClass('stdClass');
echo 'stdClass internal=', (int)$std->isInternal(), ' user=', (int)$std->isUserDefined(),
    ' instantiable=', (int)$std->isInstantiable(), ' interface=', (int)$std->isInterface(),
    ' abstract=', (int)$std->isAbstract(), ' final=', (int)$std->isFinal(),
    ' methods=', count($std->getMethods()), ' properties=', count($std->getProperties()),
    ' interfaces=', count($std->getInterfaceNames()), ' parent=', var_export($std->getParentClass(), true),
    ' file=', var_export($std->getFileName(), true), ' start=', var_export($std->getStartLine(), true), "\n";
echo 'stdClass new=', get_class($std->newInstance()), "\n";

$exception = new ReflectionClass('Exception');
echo 'Exception internal=', (int)$exception->isInternal(), ' instantiable=', (int)$exception->isInstantiable(),
    ' throwable=', (int)$exception->implementsInterface('Throwable'), "\n";

$format = new ReflectionMethod('DateTime', 'format');
echo 'DateTime format: name=', $format->getName(), ' internal=', (int)$format->isInternal(),
    ' declaring=', $format->getDeclaringClass()->getName(), ' public=', (int)$format->isPublic(), "\n";
$date = new DateTime('2020-01-02 03:04:05', new DateTimeZone('UTC'));
echo 'DateTime invoke=', $format->invoke($date, 'Y-m-d H:i:s'), "\n";

$reflectionClass = new ReflectionClass('ReflectionClass');
echo 'ReflectionClass internal=', (int)$reflectionClass->isInternal(), ' user=', (int)$reflectionClass->isUserDefined(), "\n";
echo 'ReflectionClass name=', (new ReflectionClass(new ReflectionClass('stdClass')))->getName(), "\n";
