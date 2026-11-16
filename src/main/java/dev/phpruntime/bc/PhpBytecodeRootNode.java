package dev.phpruntime.bc;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.ConstantOperand;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.bytecode.Variadic;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import dev.phpruntime.runtime.PhpArith;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;
import dev.phpruntime.runtime.PhpStrings;

/** No AST execute wrappers: the processor generates the stack interpreter and typed opcodes. */
@GenerateBytecode(languageClass = BytecodeLanguage.class, enableQuickening = true,
        enableBlockScoping = false,
        boxingEliminationTypes = {long.class, double.class, boolean.class},
        defaultLocalValue = "DEFAULT_LOCAL")
public abstract class PhpBytecodeRootNode extends RootNode implements BytecodeRootNode {
    public static final Object DEFAULT_LOCAL = PhpNull.INSTANCE;
    private String name = "<bytecode>";

    protected PhpBytecodeRootNode(BytecodeLanguage language, FrameDescriptor descriptor) {
        super(language, descriptor);
    }

    void name(String value) {
        name = value;
    }

    @Override
    public String getName() {
        return name;
    }

    /** Linked once, before any execution; supports forward calls and recursion. */
    public static final class Function {
        @CompilationFinal public RootCallTarget target;
        public final int required;
        public final int parameters;

        Function(int required, int parameters) {
            this.required = required;
            this.parameters = parameters;
        }
    }

    @Operation
    public static final class Copy {
        @Specialization static long integer(long value) { return value; }
        @Specialization static double floating(double value) { return value; }
        @Specialization static boolean bool(boolean value) { return value; }
        @Fallback static Object generic(Object value) { return PhpOps.copyOnAssign(value); }
    }

    @Operation
    public static final class Add {
        @Specialization(rewriteOn = ArithmeticException.class)
        static long integer(long a, long b) { return Math.addExact(a, b); }
        @Specialization static Object checked(long a, long b) {
            try { return Math.addExact(a, b); } catch (ArithmeticException e) { return (double) a + (double) b; }
        }
        @Specialization static double floating(double a, double b) { return a + b; }
        @Specialization static double mixed(long a, double b) { return a + b; }
        @Specialization static double mixed(double a, long b) { return a + b; }
        @Fallback static Object generic(Object a, Object b) { return PhpArith.add(a, b); }
    }

    @Operation
    public static final class Subtract {
        @Specialization(rewriteOn = ArithmeticException.class)
        static long integer(long a, long b) { return Math.subtractExact(a, b); }
        @Specialization static Object checked(long a, long b) {
            try { return Math.subtractExact(a, b); } catch (ArithmeticException e) { return (double) a - (double) b; }
        }
        @Specialization static double floating(double a, double b) { return a - b; }
        @Specialization static double mixed(long a, double b) { return a - b; }
        @Specialization static double mixed(double a, long b) { return a - b; }
        @Fallback static Object generic(Object a, Object b) { return PhpArith.sub(a, b); }
    }

    @Operation
    public static final class Multiply {
        @Specialization(rewriteOn = ArithmeticException.class)
        static long integer(long a, long b) { return Math.multiplyExact(a, b); }
        @Specialization static Object checked(long a, long b) {
            try { return Math.multiplyExact(a, b); } catch (ArithmeticException e) { return (double) a * (double) b; }
        }
        @Specialization static double floating(double a, double b) { return a * b; }
        @Specialization static double mixed(long a, double b) { return a * b; }
        @Specialization static double mixed(double a, long b) { return a * b; }
        @Fallback static Object generic(Object a, Object b) { return PhpArith.mul(a, b); }
    }

    @Operation
    public static final class Less {
        @Specialization static boolean integer(long a, long b) { return a < b; }
        @Specialization static boolean floating(double a, double b) { return a < b; }
        @Fallback static boolean generic(Object a, Object b) { return PhpOps.compare(a, b) < 0; }
    }

    @Operation
    public static final class LessEqual {
        @Specialization static boolean integer(long a, long b) { return a <= b; }
        @Specialization static boolean floating(double a, double b) { return a <= b; }
        @Fallback static boolean generic(Object a, Object b) { return PhpOps.compare(a, b) <= 0; }
    }

    @Operation
    public static final class Equal {
        @Specialization static boolean integer(long a, long b) { return a == b; }
        @Specialization static boolean floating(double a, double b) { return a == b; }
        @Fallback static boolean generic(Object a, Object b) { return PhpOps.looseEquals(a, b); }
    }

    @Operation
    public static final class Identical {
        @Specialization static boolean integer(long a, long b) { return a == b; }
        @Specialization static boolean floating(double a, double b) { return a == b; }
        @Specialization static boolean bool(boolean a, boolean b) { return a == b; }
        @Fallback static boolean generic(Object a, Object b) { return PhpOps.strictEquals(a, b); }
    }

    @Operation
    public static final class Concat {
        @Specialization static String strings(String a, String b) { return a.concat(b); }
        @Fallback static String generic(Object a, Object b) { return PhpOps.toStr(a).concat(PhpOps.toStr(b)); }
    }

    @Operation
    public static final class Truth {
        @Specialization static boolean bool(boolean value) { return value; }
        @Specialization static boolean integer(long value) { return value != 0; }
        @Specialization static boolean floating(double value) { return value != 0; }
        @Fallback static boolean generic(Object value) { return PhpOps.toBool(value); }
    }

    @Operation
    public static final class Not {
        @Specialization static boolean bool(boolean value) { return !value; }
    }

    @Operation
    @ConstantOperand(type = Function.class, name = "function")
    public static final class Call {
        @Specialization
        static Object call(Function function, @Variadic Object[] arguments,
                           @Cached("create(function.target)") DirectCallNode direct) {
            if (arguments.length < function.required || arguments.length > function.parameters) {
                throw new IllegalArgumentException("Wrong argument count in spike user call");
            }
            return direct.call(arguments);
        }
    }

    @Operation
    @ConstantOperand(type = int.class, name = "index")
    public static final class Argument {
        @Specialization static Object read(com.oracle.truffle.api.frame.VirtualFrame frame,
                                            int index, Object defaultValue) {
            return index < frame.getArguments().length ? frame.getArguments()[index] : PhpOps.copyOnAssign(defaultValue);
        }
    }

    @Operation
    public static final class Count {
        @Specialization static long count(PhpArray array) { return array.size(); }
    }

    @Operation
    public static final class IsArray {
        @Specialization static boolean test(Object value) { return value instanceof PhpArray; }
    }

    @Operation
    public static final class IsString {
        @Specialization static boolean test(Object value) { return value instanceof String; }
    }

    @Operation
    public static final class IsInt {
        @Specialization static boolean test(Object value) { return value instanceof Long; }
    }

    @Operation
    public static final class Strlen {
        @Specialization static long string(String value) { return value.length(); }
        @Fallback static long generic(Object value) { return PhpOps.toStr(value).length(); }
    }

    @Operation
    public static final class KeyExists {
        @Specialization static boolean test(Object key, PhpArray array) { return array.containsKey(key); }
    }

    @Operation
    public static final class Isset {
        @Specialization static boolean test(Object value) { return value != PhpNull.INSTANCE; }
    }

    @Operation
    public static final class NewArray {
        @Specialization static PhpArray create() { return new PhpArray(); }
    }

    /** Array literals have fresh ownership; element values still have PHP value semantics. */
    @Operation
    public static final class LiteralPut {
        @Specialization static PhpArray put(PhpArray array, Object key, Object value) {
            array.put(key, PhpOps.copyOnAssign(value));
            return array;
        }
    }

    @Operation
    public static final class LiteralAppend {
        @Specialization static PhpArray append(PhpArray array, Object value) {
            array.append(PhpOps.copyOnAssign(value));
            return array;
        }
    }

    @Operation
    public static final class Index {
        @Specialization static Object read(PhpArray array, Object key) {
            Object value = array.get(key);
            return value == null ? PhpNull.INSTANCE : value;
        }
        @Fallback static Object missing(Object value, Object key) { return PhpNull.INSTANCE; }
    }

    @Operation
    public static final class Keys {
        @Specialization static Object[] keys(@Variadic Object[] keys) { return keys; }
    }

    @Operation
    public static final class WritePath {
        @Specialization static PhpArray write(Object array, Object[] keys, Object value) {
            return writePath(array, keys, 0, value);
        }
    }

    @TruffleBoundary
    private static PhpArray writePath(Object old, Object[] keys, int depth, Object value) {
        if (old != PhpNull.INSTANCE && !(old instanceof PhpArray)) {
            throw new IllegalArgumentException("Spike only supports array/null offset writes");
        }
        PhpArray array = old instanceof PhpArray a ? a.forWrite() : new PhpArray();
        Object key = keys[depth]; // Java null is the append sentinel, distinct from PHP null.
        Object stored;
        if (depth + 1 == keys.length) {
            stored = PhpOps.copyOnAssign(value);
        } else {
            Object child = key == null ? null : array.get(key);
            stored = writePath(child == null ? PhpNull.INSTANCE : child, keys, depth + 1, value);
        }
        if (key == null) { array.append(stored); } else { array.put(key, stored); }
        return array;
    }

    public static final class Cursor {
        final PhpArray array;
        int position = -1;
        Cursor(PhpArray array) { this.array = array.share(); }
    }

    @Operation
    public static final class Iterate {
        @Specialization static Cursor start(PhpArray array) { return new Cursor(array); }
    }

    @Operation
    public static final class Advance {
        @Specialization static boolean advance(Cursor cursor) {
            cursor.position = cursor.array.nextPos(cursor.position);
            return cursor.position >= 0;
        }
    }

    @Operation
    public static final class IterKey {
        @Specialization static Object key(Cursor cursor) { return cursor.array.keyAt(cursor.position); }
    }

    @Operation
    public static final class IterValue {
        @Specialization static Object value(Cursor cursor) {
            Object value = cursor.array.rawValueAt(cursor.position);
            return PhpOps.copyOnAssign(value instanceof PhpReference ref ? ref.getValue() : value);
        }
    }

    @Operation
    public static final class EndIterate {
        @Specialization static void end(Cursor cursor) { cursor.array.releaseShare(); }
    }

    /** Benchmark instrumentation only; matches hrtime(true)'s units and monotonic clock. */
    @Operation
    public static final class Hrtime {
        @Specialization static long time(boolean numeric) {
            if (!numeric) { throw new IllegalArgumentException("Spike supports hrtime(true) only"); }
            return System.nanoTime();
        }
    }

    @Operation
    public static final class Echo {
        @Specialization
        @TruffleBoundary
        static void print(Object value, @Bind Node node) {
            try {
                BytecodeLanguage.context(node).out().write(PhpStrings.bytes(PhpOps.toStr(value)));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }
}
