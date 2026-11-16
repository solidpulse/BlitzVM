package dev.phpruntime.runtime;

/** Precomputed interface bits: engine hooks do not resolve interface names on hot paths. */
public final class SplInterfaces {
    public static final int TRAVERSABLE = 1;
    public static final int ITERATOR = 2;
    public static final int AGGREGATE = 4;
    public static final int ARRAY_ACCESS = 8;
    public static final int COUNTABLE = 16;
    public static final int JSON_SERIALIZABLE = 32;
    public static final int STRINGABLE = 64;

    private SplInterfaces() {
    }

    static int declared(String lower) {
        return switch (lower) {
            case "traversable" -> TRAVERSABLE;
            case "iterator" -> ITERATOR | TRAVERSABLE;
            case "iteratoraggregate" -> AGGREGATE | TRAVERSABLE;
            case "arrayaccess" -> ARRAY_ACCESS;
            case "countable" -> COUNTABLE;
            case "jsonserializable" -> JSON_SERIALIZABLE;
            case "stringable" -> STRINGABLE;
            default -> 0;
        };
    }

    public static boolean has(Object value, int bit) {
        return value instanceof PhpObject obj && obj.phpClass().hasSplInterface(bit);
    }
}
