<?php
// ReflectionClass::getProperties(): declaration order, filters, inheritance, promoted
// properties, identity of the returned objects, defaults and statics.

trait HasSlug
{
    public string $slug = 's';
    protected int $traitCount = 0;
}

class ParentPost
{
    public int $id = 1;
    protected string $status = 'draft';
    private string $secret = 'parent';
    public static int $instances = 0;
}

class ChildPost extends ParentPost
{
    use HasSlug;

    public string $title = 't';
    protected ?string $note = null;
    private array $tags = [];
    public static string $kind = 'post';

    public function __construct(public string $author = 'a', private int $views = 0)
    {
    }
}

function line(\ReflectionProperty $p, \ReflectionClass $owner): void
{
    $type = $p->getType();
    echo $p->getName(), '|', $p->class, '|', $p->getDeclaringClass()->getName(), '|',
        $p->getModifiers(), '|', (int) $p->isPublic(), (int) $p->isProtected(), (int) $p->isPrivate(), '|',
        (int) $p->isStatic(), (int) $p->isReadOnly(), (int) $p->isPromoted(), (int) $p->isDefault(), '|',
        $type === null ? 'none' : (string) $type, '|',
        (int) $p->hasDefaultValue(), '|',
        $p->hasDefaultValue() ? var_export($p->getDefaultValue(), true) : '-', "\n";
}

$reflection = new ReflectionClass(ChildPost::class);

foreach ($reflection->getProperties() as $property) {
    line($property, $reflection);
}

echo "public=";
foreach ($reflection->getProperties(ReflectionProperty::IS_PUBLIC) as $property) {
    echo $property->getName(), ',';
}
echo "\nprotected=";
foreach ($reflection->getProperties(ReflectionProperty::IS_PROTECTED) as $property) {
    echo $property->getName(), ',';
}
echo "\nprivate=";
foreach ($reflection->getProperties(ReflectionProperty::IS_PRIVATE) as $property) {
    echo $property->getName(), ',';
}
echo "\nstatic=";
foreach ($reflection->getProperties(ReflectionProperty::IS_STATIC) as $property) {
    echo $property->getName(), ',';
}
echo "\npublic-static=";
foreach ($reflection->getProperties(ReflectionProperty::IS_PUBLIC | ReflectionProperty::IS_STATIC) as $property) {
    echo $property->getName(), ',';
}
echo "\n";

$first = $reflection->getProperties();
$second = $reflection->getProperties();
var_dump($first[0] !== $second[0], $first[0] == $second[0]);
var_dump($first[0]->getName() === $second[0]->getName());

var_dump($reflection->hasProperty('title'), $reflection->hasProperty('secret'), $reflection->hasProperty('nope'));
$private = $reflection->getProperty('tags');
echo $private->getName(), '|', $private->getDeclaringClass()->getName(), '|', $private->isPrivate(), "\n";
$promoted = $reflection->getProperty('views');
echo $promoted->getName(), '|', (int) $promoted->isPromoted(), '|', (int) $promoted->isPrivate(), "\n";
$inherited = $reflection->getProperty('status');
echo $inherited->getName(), '|', $inherited->getDeclaringClass()->getName(), "\n";

$defaults = $reflection->getDefaultProperties();
ksort($defaults);
var_dump($defaults);

ParentPost::$instances = 3;
ChildPost::$kind = 'child';
$statics = $reflection->getStaticProperties();
ksort($statics);
var_dump($statics);

$object = new ChildPost('author');
$object->dynamic = 'dyn';
$objectReflection = new ReflectionObject($object);
var_dump($objectReflection->hasProperty('dynamic'));
$dynamic = $objectReflection->getProperty('dynamic');
echo $dynamic->getName(), '|', (int) $dynamic->isDefault(), '|', (int) $dynamic->isPublic(), '|',
    (int) $dynamic->isStatic(), '|', $dynamic->getModifiers(), "\n";
var_dump($dynamic->getValue($object));
$dynamic->setValue($object, 'changed');
var_dump($object->dynamic);

$names = [];
foreach ((new ReflectionClass(ChildPost::class))->getProperties() as $property) {
    $names[] = $property->getName();
}
sort($names);
echo implode(',', $names), "\n";

$traitProperties = array_map(fn ($p) => $p->class . '::' . $p->name, (new ReflectionClass(ChildPost::class))->getProperties(ReflectionProperty::IS_PUBLIC));
sort($traitProperties);
echo implode(',', $traitProperties), "\n";
