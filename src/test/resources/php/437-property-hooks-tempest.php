<?php

declare(strict_types=1);

// Declarations copied from tempest/framework, trimmed to what the fixture uses.

interface Kernel
{
    public string $root { get; }

    public string $internalStorage { get; }

    public Container $container { get; }

    public static function boot(
        string $root,
        array $discoveryLocations = [],
        ?Container $container = null,
        ?string $internalStorage = null,
    ): self;
}

interface Container
{
    public function get(string $className): object;
}

final class GenericContainer implements Container
{
    public function get(string $className): object
    {
        return new KernelImpl("/srv/app", "/srv/app/.tempest", $this);
    }
}

final class KernelImpl implements Kernel
{
    public function __construct(
        private(set) string $root,
        private(set) string $internalStorage,
        private(set) Container $container,
    ) {
    }

    public static function boot(
        string $root,
        array $discoveryLocations = [],
        ?Container $container = null,
        ?string $internalStorage = null,
    ): self {
        return new self($root, $internalStorage ?? $root . '/.tempest', $container ?? new GenericContainer());
    }

    public function shutdown(int|string $status = ''): void
    {
    }
}

final class IpAddress
{
    public bool $isIpv4 {
        get => strlen($this->bytes) === 4;
    }

    public bool $isIpv6 {
        get => strlen($this->bytes) === 16;
    }

    private function __construct(
        private(set) string $value,
        private(set) string $bytes,
    ) {
    }

    public static function from(string $ip): self
    {
        return new self($ip, $ip);
    }
}

final class GenericCache
{
    public function __construct(
        private(set) string $adapter,
        public bool $enabled = true,
        private ?string $deferredTasks = null,
        public string|null $tag = null,
    ) {
    }
}

$kernel = KernelImpl::boot("/srv/app");
echo $kernel->root, " ", $kernel->internalStorage, " ", get_class($kernel->container), "\n";
echo get_class($kernel), "\n";

$ip = IpAddress::from("127.0.0.1");
var_dump($ip->isIpv4, $ip->isIpv6, $ip->value);

$cache = new GenericCache("redis", false, null, "tag");
var_dump($cache->adapter, $cache->enabled, $cache->tag);

$kernelProperty = new ReflectionProperty(Kernel::class, "root");
echo "kernel root hook=", $kernelProperty->getHook(PropertyHookType::Get)?->getName() ?? "none", "\n";
$ipProperty = new ReflectionProperty(IpAddress::class, "isIpv4");
echo "ip virtual=", (int) $ipProperty->isVirtual(), "\n";
$cacheProperty = new ReflectionProperty(GenericCache::class, "adapter");
echo "cache privateSet=", (int) $cacheProperty->isPrivateSet(), "\n";
