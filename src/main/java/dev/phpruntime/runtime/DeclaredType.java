package dev.phpruntime.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.bytecode.PhpBytecodeRootNode;
import dev.phpruntime.nodes.PhpFunctionRootNode;

import java.util.ArrayList;
import java.util.List;

/**
 * A declared type prepared for checking: a flattened form of {@link PhpType} with the flags
 * the hot path needs. One instance is built per declaration (parameter, return, property) and
 * stored as a constant, so the fast checks read only these final fields.
 *
 * <p>Weak mode coercion follows php 8's rules for scalar types. The exact type is tried first
 * (a string stays a string in {@code int|string}); after that the preference is int, float,
 * string, bool, with the source type deciding where the order differs.
 */
public final class DeclaredType {

    private static final int INT = 1;
    private static final int FLOAT = 1 << 1;
    private static final int STRING = 1 << 2;
    private static final int BOOL = 1 << 3;
    private static final int ARRAY = 1 << 4;
    private static final int OBJECT = 1 << 5;
    private static final int CALLABLE = 1 << 6;
    private static final int ITERABLE = 1 << 7;
    private static final int NULL = 1 << 8;
    private static final int TRUE = 1 << 9;
    private static final int FALSE = 1 << 10;
    private static final int MIXED = 1 << 11;
    private static final int VOID = 1 << 12;
    private static final int NEVER = 1 << 13;

    /** Returned by {@link #coerceWeak} when no member of the type accepts the value. */
    private static final Object NO_MATCH = new Object();

    /** One union member made of class names that must all match (an intersection). */
    private record ClassGroup(List<String> names, int fastClassId) {
    }

    private final PhpType type;
    private final String display;
    private final String declaredClass;
    private final int mask;
    private final List<ClassGroup> classGroups;
    private final int fastClassId;
    /**
     * Class ids of every class member when each is one plain class or interface (no
     * intersection, self, parent or static), as in {@code Reflector|ReflectionType|string};
     * null otherwise. Lets an object argument to such a union skip the boundary call.
     */
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal(dimensions = 1)
    private final int[] fastClassIds;

    private DeclaredType(PhpType type, String declaredClass, PhpLanguage language) {
        this.type = type;
        this.declaredClass = declaredClass;
        this.display = display(type);
        int m = 0;
        List<ClassGroup> groups = new ArrayList<>();
        m = flatten(type, m, groups, declaredClass, language);
        this.mask = m;
        this.classGroups = List.copyOf(groups);
        int fast = -1;
        if (groups.size() == 1 && groups.get(0).names().size() == 1) {
            fast = groups.get(0).fastClassId();
        }
        this.fastClassId = fast;
        int[] ids = groups.isEmpty() ? null : new int[groups.size()];
        for (int i = 0; ids != null && i < groups.size(); i++) {
            ClassGroup g = groups.get(i);
            if (g.names().size() != 1 || g.fastClassId() < 0) {
                ids = null;
            } else {
                ids[i] = g.fastClassId();
            }
        }
        this.fastClassIds = fast >= 0 ? new int[]{fast} : ids;
    }

    public static DeclaredType of(PhpType type, String declaredClass, PhpLanguage language) {
        return type == null ? null : new DeclaredType(type, declaredClass, language);
    }

    public PhpType type() {
        return type;
    }

    public String display() {
        return display;
    }

    public boolean isMixed() {
        return (mask & MIXED) != 0;
    }

    public boolean isVoid() {
        return (mask & VOID) != 0;
    }

    public boolean isNever() {
        return (mask & NEVER) != 0;
    }

    public boolean acceptsLong() {
        return (mask & INT) != 0;
    }

    public boolean acceptsDouble() {
        return (mask & FLOAT) != 0;
    }

    public boolean acceptsString() {
        return (mask & STRING) != 0;
    }

    public boolean acceptsBool() {
        return (mask & BOOL) != 0;
    }

    public boolean acceptsArray() {
        return (mask & (ARRAY | ITERABLE)) != 0;
    }

    public boolean acceptsNull() {
        return (mask & NULL) != 0;
    }

    public boolean acceptsAnyObject() {
        return (mask & OBJECT) != 0;
    }

    /** Whether the class fast path can run (every class member one plain class/interface). */
    public boolean hasFastClass() {
        return fastClassIds != null;
    }

    /**
     * The cheap guard for an object of a declared class (or a subclass/implementor). A class not
     * declared in this request has no instances, so an unresolved member simply does not match;
     * the caller's slow path decides then.
     */
    public boolean fastClassMatches(PhpObject value, PhpContext ctx, PhpClass selfClass) {
        if (fastClassId >= 0) {
            PhpClass target = ctx.classById(fastClassId);
            if (target == null && selfClass != null) {
                // A self/parent type left unresolved in a trait.
                target = resolveGroupName(0, selfClass, null, ctx);
            }
            return target != null && value.phpClass().isInstanceOf(target);
        }
        return anyClassMatches(value.phpClass(), ctx);
    }

    @com.oracle.truffle.api.nodes.ExplodeLoop
    private boolean anyClassMatches(PhpClass cls, PhpContext ctx) {
        int[] ids = fastClassIds;
        for (int i = 0; i < ids.length; i++) {
            PhpClass target = ctx.classById(ids[i]);
            if (target != null && cls.isInstanceOf(target)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ fast matching

    /** Whether {@code value} already is one of the declared types, without resolving classes. */
    public boolean matchesFast(Object value) {
        if ((mask & MIXED) != 0) {
            return true;
        }
        if (value == null || value == PhpNull.INSTANCE) {
            return (mask & NULL) != 0;
        }
        if (value instanceof Long) {
            return (mask & INT) != 0;
        }
        if (value instanceof Double) {
            return (mask & FLOAT) != 0;
        }
        if (value instanceof String) {
            return (mask & STRING) != 0;
        }
        if (value instanceof Boolean b) {
            return (mask & BOOL) != 0 || (b ? (mask & TRUE) != 0 : (mask & FALSE) != 0);
        }
        if (value instanceof PhpArray) {
            return (mask & (ARRAY | ITERABLE)) != 0;
        }
        return false;
    }

    // ------------------------------------------------------------------ full matching

    /** Whether {@code value} satisfies the type, resolving class names and callables. */
    @TruffleBoundary
    public boolean matches(Object value, PhpContext ctx, PhpClass selfClass, PhpClass calledClass) {
        Object v = value == null ? PhpNull.INSTANCE : value;
        if ((mask & MIXED) != 0) {
            return true;
        }
        if (v == PhpNull.INSTANCE) {
            return (mask & NULL) != 0;
        }
        if (v instanceof Long) {
            if ((mask & INT) != 0) {
                return true;
            }
        } else if (v instanceof Double) {
            if ((mask & FLOAT) != 0) {
                return true;
            }
        } else if (v instanceof String) {
            if ((mask & STRING) != 0) {
                return true;
            }
        } else if (v instanceof Boolean b) {
            if ((mask & BOOL) != 0 || (b ? (mask & TRUE) != 0 : (mask & FALSE) != 0)) {
                return true;
            }
        } else if (v instanceof PhpArray) {
            if ((mask & (ARRAY | ITERABLE)) != 0) {
                return true;
            }
        } else if (v instanceof PhpObject obj) {
            if ((mask & OBJECT) != 0) {
                return true;
            }
            if ((mask & ITERABLE) != 0 && obj.phpClass().hasSplInterface(SplInterfaces.TRAVERSABLE)) {
                return true;
            }
            if ((mask & CALLABLE) != 0 && PhpCallable.resolveOrNull(ctx, v) != null) {
                return true;
            }
        }
        if ((mask & CALLABLE) != 0 && !(v instanceof PhpObject) && PhpCallable.resolveOrNull(ctx, v) != null) {
            return true;
        }
        for (int i = 0; i < classGroups.size(); i++) {
            if (matchesGroup(i, v, ctx, selfClass, calledClass)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesGroup(int index, Object value, PhpContext ctx, PhpClass selfClass, PhpClass calledClass) {
        if (!(value instanceof PhpObject obj)) {
            return false;
        }
        ClassGroup group = classGroups.get(index);
        if (group.fastClassId() >= 0) {
            // A single named class: the context's id table finds a declared class without
            // lowercasing and hashing the name; autoload still goes through resolveClass.
            PhpClass target = ctx.classById(group.fastClassId());
            if (target != null) {
                return obj.phpClass().isInstanceOf(target);
            }
        }
        for (String name : group.names()) {
            PhpClass target = resolveClass(name, ctx, selfClass, calledClass);
            if (target == null || !obj.phpClass().isInstanceOf(target)) {
                return false;
            }
        }
        return true;
    }

    private PhpClass resolveGroupName(int index, PhpClass selfClass, PhpClass calledClass, PhpContext ctx) {
        ClassGroup group = classGroups.get(index);
        return group.names().isEmpty() ? null : resolveClass(group.names().get(0), ctx, selfClass, calledClass);
    }

    private PhpClass resolveClass(String name, PhpContext ctx, PhpClass selfClass, PhpClass calledClass) {
        String lower = PhpStrings.lower(name);
        return switch (lower) {
            case "self" -> selfClass;
            case "parent" -> {
                PhpClass self = selfClass;
                yield self != null ? self.parent() : null;
            }
            case "static" -> calledClass != null ? calledClass : selfClass;
            default -> ctx.lookupOrAutoloadClass(name);
        };
    }

    // ------------------------------------------------------------------ coercion

    /**
     * Returns {@code value} unchanged when it matches, the coerced value in weak mode, or
     * {@link #NO_MATCH}. Strict mode only converts int to float here; the caller reports the
     * error.
     */
    @TruffleBoundary
    public Object matchOrCoerce(Object value, PhpContext ctx, PhpClass selfClass, PhpClass calledClass,
                                boolean strict, int line) {
        Object v = value == null ? PhpNull.INSTANCE : value;
        if (matches(v, ctx, selfClass, calledClass)) {
            return v;
        }
        if (strict) {
            if (v instanceof Long l && (mask & FLOAT) != 0 && (mask & INT) == 0) {
                return (double) l;
            }
            return NO_MATCH;
        }
        return coerceWeak(v, ctx, line);
    }

    private Object coerceWeak(Object v, PhpContext ctx, int line) {
        boolean canInt = (mask & INT) != 0;
        boolean canFloat = (mask & FLOAT) != 0;
        boolean canString = (mask & STRING) != 0;
        boolean canBool = (mask & BOOL) != 0;
        if (v instanceof Boolean b) {
            long l = b ? 1 : 0;
            if (canInt) {
                return l;
            }
            if (canFloat) {
                return (double) l;
            }
            if (canString) {
                return b ? "1" : "";
            }
            return NO_MATCH;
        }
        if (v instanceof Long l) {
            if (canFloat) {
                return (double) l;
            }
            if (canString) {
                return Long.toString(l);
            }
            if (canBool) {
                return l != 0;
            }
            return NO_MATCH;
        }
        if (v instanceof Double d) {
            if (canFloat) {
                return d;
            }
            if (canInt) {
                if (Double.isNaN(d) || Double.isInfinite(d) || d < Long.MIN_VALUE || d > Long.MAX_VALUE) {
                    return NO_MATCH;
                }
                long l = (long) d.doubleValue();
                if (l != d) {
                    implicitFloatToInt(ctx, d, line);
                }
                return l;
            }
            if (canString) {
                return PhpOps.toStr(d);
            }
            if (canBool) {
                return d != 0.0;
            }
            return NO_MATCH;
        }
        if (v instanceof String s) {
            boolean numeric = PhpOps.isNumericString(s);
            if (numeric) {
                Object number = PhpOps.parseNumericPrefix(s);
                if (number instanceof Long l) {
                    if (canInt) {
                        return l;
                    }
                    if (canFloat) {
                        return (double) l;
                    }
                } else if (number instanceof Double d) {
                    if (canFloat) {
                        return d;
                    }
                    if (canInt) {
                        long l = (long) d.doubleValue();
                        if (l != d) {
                            implicitFloatStringToInt(ctx, s, line);
                        }
                        return l;
                    }
                }
            }
            if (canBool) {
                return PhpOps.toBool(s);
            }
            return NO_MATCH;
        }
        if (v instanceof PhpObject obj) {
            if (canString && obj.phpClass().toStringMethod() != null) {
                return PhpOps.toStr(obj);
            }
            return NO_MATCH;
        }
        return NO_MATCH;
    }

    private static void implicitFloatToInt(PhpContext ctx, double value, int line) {
        if (ctx != null) {
            ctx.deprecated("Implicit conversion from float " + PhpOps.formatDouble(value, 14)
                    + " to int loses precision", line);
        }
    }

    private static void implicitFloatStringToInt(PhpContext ctx, String value, int line) {
        if (ctx != null) {
            ctx.deprecated("Implicit conversion from float-string \"" + value
                    + "\" to int loses precision", line);
        }
    }

    // ------------------------------------------------------------------ checks by position

    /**
     * Checks a parameter argument. On failure throws php's TypeError, whose {@code getFile()}
     * and {@code getLine()} point at the declaration while the message names the call site.
     */
    @TruffleBoundary
    public Object checkParameter(Object value, PhpBytecodeRootNode root, Node site, int paramIndex,
                                 String paramName, PhpClass selfClass, PhpClass calledClass) {
        PhpContext ctx = PhpContext.get(site);
        PhpFunctionRootNode fn = root.function();
        // A value strict mode accepts (an exact match, or an int widened to float) comes out the
        // same in weak mode, so the caller's mode only matters when that fails. Finding it walks
        // the stack, which typed code would otherwise pay on every class-typed argument.
        // The strict pass never coerces, so it needs no line; looking one up costs a source scan.
        Object strictResult = matchOrCoerce(value, ctx, selfClass, calledClass, true, 0);
        if (strictResult != NO_MATCH) {
            return strictResult;
        }
        if (!CallMode.strictOfCallerOf(root, site)) {
            Object result = matchOrCoerce(value, ctx, selfClass, calledClass, false, fn.line());
            if (result != NO_MATCH) {
                return result;
            }
        }
        CallMode mode = CallMode.of(root, site);
        StringBuilder msg = new StringBuilder(fn.qualifiedCallableName()).append("(): Argument #")
                .append(paramIndex + 1);
        if (paramName != null) {
            msg.append(" ($").append(paramName).append(')');
        }
        msg.append(" must be of type ").append(display).append(", ")
                .append(givenType(value)).append(" given");
        if (mode.file() != null && mode.line() > 0) {
            msg.append(", called in ").append(mode.file()).append(" on line ").append(mode.line());
        }
        String file = fn.source() != null ? fn.source().getPath() : null;
        throw new PhpError(PhpError.TYPE_ERROR, msg.toString(), file, fn.line(), null);
    }

    /** Checks a returned value. The mode is always the declaring file's. */
    @TruffleBoundary
    public Object checkReturn(Object value, PhpBytecodeRootNode root, Node site, boolean implicit,
                              PhpClass selfClass, PhpClass calledClass) {
        Object v = value == null ? PhpNull.INSTANCE : value;
        if (isNever()) {
            String msg = root.function().qualifiedCallableName() + (implicit
                    ? "(): never-returning function must not implicitly return"
                    : "(): never-returning function must not return");
            throw new PhpError(PhpError.TYPE_ERROR, msg, site);
        }
        if (isVoid()) {
            if (v != PhpNull.INSTANCE) {
                throw new PhpError(PhpError.TYPE_ERROR,
                        root.function().qualifiedCallableName() + "(): A void function must not return a value", site);
            }
            return PhpNull.INSTANCE;
        }
        PhpContext ctx = PhpContext.get(site);
        if (matches(v, ctx, selfClass, calledClass)) {
            return v;
        }
        // The line is only for a weak-mode coercion's deprecation.
        int line = Math.max(0, PhpBacktrace.sourceLine(site));
        Object result = matchOrCoerce(v, ctx, selfClass, calledClass, root.strictTypes(), line);
        if (result != NO_MATCH) {
            return result;
        }
        String given = implicit && v == PhpNull.INSTANCE ? "none returned"
                : givenType(v) + " returned";
        String msg = root.function().qualifiedCallableName() + "(): Return value must be of type "
                + display + ", " + given;
        throw new PhpError(PhpError.TYPE_ERROR, msg, site);
    }

    /** Checks the Generator object a generator function returns, against its declared type. */
    @TruffleBoundary
    public void checkGeneratorReturn(Object value, PhpFunctionRootNode fn, Node site, PhpContext ctx) {
        PhpClass self = fn.declaringClass() == null ? null : ctx.lookupClass(fn.declaringClass());
        if (matches(value, ctx, self, null)) {
            return;
        }
        throw new PhpError(PhpError.TYPE_ERROR, fn.qualifiedCallableName()
                + "(): Return value must be of type " + display + ", Generator returned", site);
    }

    /** Checks a value stored into a typed property or static property. */
    @TruffleBoundary
    public Object checkProperty(Object value, PhpContext ctx, Node site, boolean strict, PhpClass selfClass,
                                String owner, String property) {
        Object v = value == null ? PhpNull.INSTANCE : value;
        if (matchesFast(v) || matches(v, ctx, selfClass, null)) {
            return v;
        }
        int line = site == null ? 0 : Math.max(0, PhpBacktrace.sourceLine(site));
        Object result = matchOrCoerce(v, ctx, selfClass, null, strict, line);
        if (result != NO_MATCH) {
            return result;
        }
        throw new PhpError(PhpError.TYPE_ERROR, "Cannot assign " + givenType(v) + " to property "
                + owner + "::$" + property + " of type " + display, site);
    }

    /** A reference cell created for a typed property: every write is checked like a property write. */
    public PhpReference.CellCheck propertyCellCheck(PhpContext ctx, boolean strict, PhpClass selfClass,
                                                    String owner, String property) {
        return new PhpReference.CellCheck(this, ctx, strict, selfClass, owner, property);
    }

    /** Whether a {@code void} value is acceptable (used by the emitter's implicit return). */
    public boolean acceptsNoValue() {
        return (mask & (VOID | NULL | MIXED)) != 0 || matchesFast(PhpNull.INSTANCE);
    }

    // ------------------------------------------------------------------ display

    private static String givenType(Object value) {
        if (value == Boolean.TRUE) {
            return "true";
        }
        if (value == Boolean.FALSE) {
            return "false";
        }
        if (value == null) {
            return "null";
        }
        return PhpOps.typeDescription(value);
    }

    /** The type as php's error messages print it: iterable reads Traversable|array. */
    private static String display(PhpType type) {
        return normalize(type).toString();
    }

    private static PhpType normalize(PhpType type) {
        if (type instanceof PhpType.Named named) {
            String lower = PhpStrings.lower(named.name());
            if (lower.equals("iterable")) {
                return new PhpType.Union(List.of(new PhpType.Named("Traversable"), new PhpType.Named("array")));
            }
            return type;
        }
        if (type instanceof PhpType.Nullable nullable) {
            PhpType inner = normalize(nullable.inner());
            if (inner instanceof PhpType.Union union) {
                List<PhpType> members = new ArrayList<>(union.members());
                members.add(new PhpType.Named("null"));
                return new PhpType.Union(members);
            }
            return new PhpType.Nullable(inner);
        }
        if (type instanceof PhpType.Union union) {
            List<PhpType> members = new ArrayList<>();
            for (PhpType member : union.members()) {
                PhpType n = normalize(member);
                if (n instanceof PhpType.Union nested) {
                    members.addAll(nested.members());
                } else {
                    members.add(n);
                }
            }
            return new PhpType.Union(members);
        }
        if (type instanceof PhpType.Intersection intersection) {
            List<PhpType> members = new ArrayList<>();
            for (PhpType member : intersection.members()) {
                members.add(normalize(member));
            }
            return new PhpType.Intersection(members);
        }
        return type;
    }

    // ------------------------------------------------------------------ flattening

    private static int flatten(PhpType type, int mask, List<ClassGroup> groups, String declaredClass,
                               PhpLanguage language) {
        if (type instanceof PhpType.Nullable nullable) {
            return flatten(nullable.inner(), mask | NULL, groups, declaredClass, language);
        }
        if (type instanceof PhpType.Union union) {
            int m = mask;
            for (PhpType member : union.members()) {
                m = flatten(member, m, groups, declaredClass, language);
            }
            return m;
        }
        if (type instanceof PhpType.Intersection intersection) {
            List<String> names = new ArrayList<>(intersection.members().size());
            for (PhpType member : intersection.members()) {
                names.add(member instanceof PhpType.Named named ? named.name() : member.toString());
            }
            groups.add(new ClassGroup(names, classIdOf(names, language)));
            return mask;
        }
        if (type instanceof PhpType.Named named) {
            String name = named.name();
            String lower = PhpStrings.lower(name);
            return switch (lower) {
                case "int" -> mask | INT;
                case "float" -> mask | FLOAT;
                case "string" -> mask | STRING;
                case "bool" -> mask | BOOL;
                case "array" -> mask | ARRAY;
                case "object" -> mask | OBJECT;
                case "callable" -> mask | CALLABLE;
                case "iterable" -> mask | ITERABLE;
                case "mixed" -> mask | MIXED;
                case "void" -> mask | VOID;
                case "never" -> mask | NEVER;
                case "null" -> mask | NULL;
                case "true" -> mask | TRUE;
                case "false" -> mask | FALSE;
                case "self", "parent", "static" -> {
                    groups.add(new ClassGroup(List.of(name), -1));
                    yield mask;
                }
                default -> {
                    groups.add(new ClassGroup(List.of(name), classIdOf(List.of(name), language)));
                    yield mask;
                }
            };
        }
        throw new IllegalArgumentException("unknown type " + type);
    }

    private static int classIdOf(List<String> names, PhpLanguage language) {
        if (language == null || names.size() != 1) {
            return -1;
        }
        String name = names.get(0);
        String lower = PhpStrings.lower(name);
        if (lower.equals("self") || lower.equals("parent") || lower.equals("static")
                || name.startsWith("?")) {
            return -1;
        }
        return language.classId(name);
    }

    @Override
    public String toString() {
        return display;
    }
}
