package dev.phpruntime.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A PHP type declaration as written, kept as metadata for the Reflection API.
 *
 * <p>The parser resolves class names the same way it resolves class references
 * elsewhere (namespace and {@code use} imports, case preserved) and normalises the
 * built-in type names to lowercase. {@link #toString()} produces the form PHP's
 * {@code ReflectionType::__toString()} gives: nullable types use {@code ?T} when the
 * type is a single name plus null, union members are ordered classes first (in
 * declaration order), then {@code static}, the built-in types in PHP's canonical
 * order, and {@code null} last.
 */
public sealed interface PhpType permits PhpType.Named, PhpType.Nullable, PhpType.Union,
        PhpType.Intersection {

    /** The string PHP's {@code ReflectionType::__toString()} returns for this type. */
    @Override
    String toString();

    /** A class or built-in type name, resolved and with the case it was written in. */
    record Named(String name) implements PhpType {
        public Named {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("type name");
            }
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** {@code ?T}. */
    record Nullable(PhpType inner) implements PhpType {
        public Nullable {
            if (inner == null) {
                throw new IllegalArgumentException("inner type");
            }
        }

        @Override
        public String toString() {
            return "?" + inner;
        }
    }

    /** {@code A|B}; members stay in the order the source wrote them. */
    record Union(List<PhpType> members) implements PhpType {
        public Union {
            members = List.copyOf(members);
            if (members.isEmpty()) {
                throw new IllegalArgumentException("empty union");
            }
        }

        @Override
        public String toString() {
            List<String> classes = new ArrayList<>();
            List<Named> builtins = new ArrayList<>();
            String staticName = null;
            String nullName = null;
            for (PhpType member : members) {
                if (member instanceof Named named) {
                    String lower = lower(named.name());
                    if (lower.equals("null")) {
                        nullName = named.name();
                    } else if (lower.equals("static")) {
                        staticName = named.name();
                    } else if (builtinRank(lower) >= 0) {
                        builtins.add(named);
                    } else {
                        classes.add(named.name());
                    }
                } else if (member instanceof Intersection intersection) {
                    classes.add("(" + intersection + ")");
                } else {
                    classes.add(member.toString());
                }
            }
            builtins.sort(Comparator.comparingInt(n -> builtinRank(lower(n.name()))));
            if (staticName != null) {
                classes.add(staticName);
            }
            for (Named builtin : builtins) {
                classes.add(builtin.name());
            }
            if (nullName != null) {
                classes.add(nullName);
            }
            return String.join("|", classes);
        }
    }

    /** {@code A&B}; only named types can take part in an intersection. */
    record Intersection(List<PhpType> members) implements PhpType {
        public Intersection {
            members = List.copyOf(members);
            if (members.isEmpty()) {
                throw new IllegalArgumentException("empty intersection");
            }
        }

        @Override
        public String toString() {
            List<String> names = new ArrayList<>(members.size());
            for (PhpType member : members) {
                names.add(member.toString());
            }
            return String.join("&", names);
        }
    }

    /** A single non-null type with the nullable marker, {@code ?T}. */
    static PhpType nullable(PhpType inner) {
        return new Nullable(inner);
    }

    /**
     * Builds a union the way the engine does before reflection sees it: nested unions
     * are flattened, {@code iterable} expands to {@code Traversable|array}, and a
     * two-member union of one named type and null collapses to {@code ?T}. The
     * explicit form {@code ?iterable} stays a {@link Nullable} because the parser
     * never puts it through this method.
     */
    static PhpType union(List<PhpType> written) {
        List<PhpType> members = new ArrayList<>();
        for (PhpType type : written) {
            if (type instanceof Union nested) {
                members.addAll(nested.members());
            } else if (type instanceof Named named && lower(named.name()).equals("iterable")) {
                members.add(new Named("Traversable"));
                members.add(new Named("array"));
            } else {
                members.add(type);
            }
        }
        if (members.size() == 2) {
            if (isNull(members.get(0)) && members.get(1) instanceof Named named) {
                return new Nullable(named);
            }
            if (isNull(members.get(1)) && members.get(0) instanceof Named named) {
                return new Nullable(named);
            }
        }
        return new Union(members);
    }

    /** Builds an intersection from the named types written between ampersands. */
    static PhpType intersection(List<PhpType> members) {
        return new Intersection(members);
    }

    /** Whether {@code name} is one of PHP's built-in type names (case-insensitive). */
    static boolean isBuiltinName(String name) {
        return switch (lower(name)) {
            case "int", "float", "string", "bool", "array", "callable", "iterable", "object",
                    "mixed", "void", "never", "null", "false", "true", "static", "self", "parent" -> true;
            default -> false;
        };
    }

    private static boolean isNull(PhpType type) {
        return type instanceof Named named && lower(named.name()).equals("null");
    }

    private static int builtinRank(String lower) {
        return switch (lower) {
            case "callable" -> 0;
            case "object" -> 1;
            case "array" -> 2;
            case "string" -> 3;
            case "int" -> 4;
            case "float" -> 5;
            case "bool" -> 6;
            case "false" -> 7;
            case "true" -> 8;
            default -> -1;
        };
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
