<?php
// ReflectionClass construction: by name, by object, autoloading and the class kinds.

namespace Shop\Model;

interface Repo
{
    public function label(): string;
}

trait Greets
{
    public function greet(): string
    {
        return 'hello';
    }
}

enum Suit: string
{
    case Hearts = 'H';
    case Spades = 'S';
}

abstract class Base
{
    public function base(): string
    {
        return 'base';
    }
}

final class Post extends Base implements Repo
{
    use Greets;

    public function __construct(public string $title = 'untitled')
    {
    }

    public function label(): string
    {
        return $this->title;
    }
}

function show(\ReflectionClass $r): void
{
    echo $r->getName(), '|', $r->getShortName(), '|', $r->getNamespaceName(), '|',
        (int) $r->inNamespace(), '|', (int) $r->isInterface(), (int) $r->isTrait(), '|',
        (int) $r->isEnum(), (int) $r->isFinal(), "\n";
}

$post = new Post('hi');

show(new \ReflectionClass(Post::class));
show(new \ReflectionClass('Shop\Model\Post'));
show(new \ReflectionClass('shop\model\post'));
show(new \ReflectionClass('\Shop\Model\Post'));
show(new \ReflectionClass($post));
show(new \ReflectionObject($post));
show(new \ReflectionClass(Repo::class));
show(new \ReflectionClass('Shop\Model\Repo'));
show(new \ReflectionClass(Greets::class));
show(new \ReflectionClass(Suit::class));

$anonymous = new class extends Base {
};
$anonymousReflection = new \ReflectionClass($anonymous);
echo 'anonymous=', (int) $anonymousReflection->isAnonymous(), '|',
    (int) $anonymousReflection->isFinal(), '|', $anonymousReflection->getParentClass()->getName(), "\n";

spl_autoload_register(function (string $name): void {
    echo 'autoload:', $name, "\n";
    if ($name === 'LazyWidget') {
        eval('class LazyWidget { public int $n = 1; }');
    }
});

show(new \ReflectionClass('LazyWidget'));
show(new \ReflectionClass('lazywidget'));

try {
    new \ReflectionClass('NoSuchClass');
} catch (\ReflectionException $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

try {
    new \ReflectionClass('Shop\Model\Nope');
} catch (\ReflectionException $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

try {
    new \ReflectionClass('not a name');
} catch (\ReflectionException $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

var_dump((new \ReflectionClass(Post::class))->isInstantiable());
var_dump((new \ReflectionClass(Base::class))->isInstantiable());
var_dump((new \ReflectionClass(Repo::class))->isInstantiable());
var_dump((new \ReflectionClass(Suit::class))->isInstantiable());
echo (new \ReflectionClass(Post::class))->getFileName() !== false ? "file\n" : "internal\n";
echo (new \ReflectionClass($post))->getStartLine() > 0 ? "line\n" : "noline\n";
