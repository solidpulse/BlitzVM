package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.runtime.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** SPL array methods are inlineable; callback sorting and filesystem work have their own boundaries. */
public final class SplMethodRootNode extends RootNode {
    public final String owner;
    public final String method;
    private final int min;
    private final int max;

    public SplMethodRootNode(PhpLanguage language, String owner, String method, int min, int max) {
        super(language);
        this.owner = owner;
        this.method = method;
        this.min = min;
        this.max = max;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (!PhpStrings.CHECK) { return executeMethod(frame); }
        PhpStrings.checkArguments(frame.getArguments(), owner + "::" + method);
        return PhpStrings.checkReturn(executeMethod(frame), owner + "::" + method);
    }

    private Object executeMethod(VirtualFrame frame) {
        Object[] raw = frame.getArguments();
        PhpObject self = (PhpObject) raw[0];
        int n = raw.length - 2;
        if (n < min || (max >= 0 && n > max)) {
            int expected = n < min ? min : max;
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError(PhpError.ARGUMENT_COUNT, owner + "::" + method + "() expects "
                    + (min == max ? "exactly " : n < min ? "at least " : "at most ")
                    + expected + " argument" + (expected == 1 ? "" : "s") + ", " + n + " given", this);
        }
        PhpContext ctx = PhpContext.get(this);
        Object a = n > 0 ? raw[2] : PhpNull.INSTANCE;
        Object b = n > 1 ? raw[3] : PhpNull.INSTANCE;
        if (method.equals("__construct")) {
            int flags = n > 1 ? (int) PhpOps.toLong(b) : 0;
            Object source = n > 0 ? a : new PhpArray();
            if (!(source instanceof PhpArray || source instanceof PhpObject)) {
                com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                throw new PhpError(PhpError.TYPE_ERROR, owner + "::__construct(): Argument #1 ($array) must be of type array|object, "
                        + PhpOps.typeDescription(source) + " given", this);
            }
            SplArrayState state = new SplArrayState(source, flags);
            if (n > 2) {
                state.iteratorClass = PhpOps.toStr(raw[4]);
                PhpClass iteratorClass = ctx.lookupOrAutoloadClass(state.iteratorClass);
                if (iteratorClass == null || !iteratorClass.isInstanceOf(ctx.lookupClass("ArrayIterator"))) {
                    com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                    throw new PhpError(PhpError.TYPE_ERROR, "ArrayObject::__construct(): Argument #3 ($iteratorClass) must be a class name derived from ArrayIterator", this);
                }
            }
            self.setNativeState(state);
            return PhpNull.INSTANCE;
        }
        if (self.nativeState() == null && method.equals("__unserialize")) {
            self.setNativeState(new SplArrayState(new PhpArray(), 0));
        }
        if (!(self.nativeState() instanceof SplArrayState state)) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError("The parent constructor was not called: the object is in an invalid state", this);
        }
        return switch (method) {
            case "rewind" -> { state.position = 0; yield PhpNull.INSTANCE; }
            case "valid" -> state.valid();
            case "key" -> state.key();
            case "current" -> state.valid() ? nullValue(state.current()) : PhpNull.INSTANCE;
            case "next" -> { if (state.valid()) { state.position++; } yield PhpNull.INSTANCE; }
            case "count" -> state.count();
            case "offsetExists" -> state.exists(a);
            case "offsetGet" -> {
                Object value = state.get(a);
                if (value == null) {
                    ctx.warning("Undefined array key " + (a instanceof String ? "\"" + a + "\"" : PhpOps.toStr(a)), 0);
                }
                yield nullValue(value);
            }
            case "offsetSet" -> { state.set(a, b); yield PhpNull.INSTANCE; }
            case "offsetUnset" -> { state.unset(a); yield PhpNull.INSTANCE; }
            case "append" -> { state.set(PhpNull.INSTANCE, a); yield PhpNull.INSTANCE; }
            case "getArrayCopy" -> state.arrayCopy();
            case "serialize" -> {
                dev.phpruntime.builtins.Builtins.Def serialize = dev.phpruntime.builtins.Builtins.lookup("serialize");
                yield "x:i:" + state.flags + ";" + serialize.impl().call(ctx, new Object[]{state.arrayCopy()})
                        + ";m:" + serialize.impl().call(ctx, new Object[]{dev.phpruntime.builtins.SplNativeSupport.properties(self)});
            }
            case "unserialize" -> {
                dev.phpruntime.builtins.SplNativeSupport.restoreArray(ctx, self, state, PhpOps.toStr(a));
                yield PhpNull.INSTANCE;
            }
            case "getFlags" -> (long) state.flags;
            case "__serialize" -> {
                PhpArray out = new PhpArray();
                out.put(0L, (long) state.flags);
                out.put(1L, state.arrayCopy());
                out.put(2L, dev.phpruntime.builtins.SplNativeSupport.properties(self));
                out.put(3L, owner.equals("ArrayObject") && !state.iteratorClass.equals("ArrayIterator")
                        ? state.iteratorClass : PhpNull.INSTANCE);
                yield out;
            }
            case "__unserialize" -> {
                if (!(a instanceof PhpArray data)) {
                    throw new PhpError(PhpError.TYPE_ERROR, owner + "::__unserialize(): Argument #1 ($data) must be of type array, "
                            + PhpOps.typeDescription(a) + " given", this);
                }
                Object source = data.get(0L);
                Object storage = data.get(1L);
                Object props = data.get(2L);
                Object iteratorClass = data.get(3L);
                int flags = source instanceof Long f ? (int) f.longValue() : 0;
                if (!(storage instanceof PhpArray || storage instanceof PhpObject)) {
                    throw new PhpError(PhpError.TYPE_ERROR, owner
                            + "::__unserialize(): Argument #2 ($data) must be an array or object", this);
                }
                SplArrayState replacement = new SplArrayState(storage, flags);
                if (iteratorClass instanceof String name && owner.equals("ArrayObject")) {
                    replacement.iteratorClass = name;
                }
                replacement.position = 0;
                self.setNativeState(replacement);
                if (props instanceof PhpArray values) {
                    com.oracle.truffle.api.object.DynamicObject.PutNode put =
                            com.oracle.truffle.api.object.DynamicObject.PutNode.getUncached();
                    for (var entry : values.entries()) {
                        put.execute(self, PhpOps.toStr(entry.getKey()), PhpOps.copyOnAssign(entry.getValue()));
                    }
                }
                yield PhpNull.INSTANCE;
            }
            case "setFlags" -> { state.flags = (int) PhpOps.toLong(a); yield PhpNull.INSTANCE; }
            case "seek" -> {
                long pos = PhpOps.toLong(a);
                if (pos < 0 || pos >= state.count()) {
                    com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                    throw new PhpError("OutOfBoundsException", "Seek position " + pos + " is out of range", this);
                }
                state.position = (int) pos;
                yield PhpNull.INSTANCE;
            }
            case "getIterator" -> {
                PhpClass cls = ctx.lookupOrAutoloadClass(state.iteratorClass);
                if (cls == null) { throw new PhpError("Class \"" + state.iteratorClass + "\" not found", this); }
                if (!cls.isInstanceOf(ctx.lookupClass("ArrayIterator"))) {
                    com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                    throw new PhpError(PhpError.TYPE_ERROR, "ArrayObject::__construct(): Argument #3 ($iteratorClass) must be a class name derived from ArrayIterator", this);
                }
                PhpObject iterator = new PhpObject(cls, ctx.nextObjectId());
                cls.populateDefaults(iterator);
                iterator.setNativeState(new SplArrayState(state.storage, state.flags));
                yield iterator;
            }
            case "getIteratorClass" -> state.iteratorClass;
            case "setIteratorClass" -> {
                String name = PhpOps.toStr(a);
                PhpClass cls = ctx.lookupOrAutoloadClass(name);
                if (cls == null || !cls.isInstanceOf(ctx.lookupClass("ArrayIterator"))) {
                    com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                    throw new PhpError(PhpError.TYPE_ERROR, "ArrayObject::setIteratorClass(): Argument #1 ($iteratorClass) must be a class name derived from ArrayIterator", this);
                }
                state.iteratorClass = cls.name();
                yield PhpNull.INSTANCE;
            }
            case "exchangeArray" -> {
                if (!(a instanceof PhpArray || a instanceof PhpObject)) {
                    com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                    throw new PhpError(PhpError.TYPE_ERROR, "ArrayObject::exchangeArray(): Argument #1 ($array) must be of type array|object, "
                            + PhpOps.typeDescription(a) + " given", this);
                }
                PhpArray previous = state.arrayCopy();
                SplArrayState.Storage replacement = new SplArrayState.Storage(a);
                state.storage.array = replacement.array;
                state.storage.object = replacement.object;
                state.storage.size = replacement.size;
                state.storage.version++;
                state.position = 0;
                yield previous;
            }
            case "hasChildren" -> {
                Object child = state.valid() ? state.get(state.key()) : null;
                yield child instanceof PhpArray || (child instanceof PhpObject && (state.flags & SplArrayState.CHILD_ARRAYS_ONLY) == 0);
            }
            case "getChildren" -> {
                Object child = state.valid() ? state.get(state.key()) : null;
                if (!(child instanceof PhpArray || child instanceof PhpObject)) { yield PhpNull.INSTANCE; }
                PhpObject iterator = new PhpObject(self.phpClass(), ctx.nextObjectId());
                self.phpClass().populateDefaults(iterator);
                iterator.setNativeState(new SplArrayState(child, state.flags));
                yield iterator;
            }
            case "asort", "ksort", "uasort", "uksort", "natsort", "natcasesort" -> sort(ctx, state, a);
            default -> throw new IllegalStateException(owner + "::" + method);
        };
    }

    private static Object nullValue(Object value) {
        return value == null ? PhpNull.INSTANCE : value;
    }

    @TruffleBoundary
    private Object sort(PhpContext ctx, SplArrayState state, Object arg) {
        List<Map.Entry<Object, Object>> entries = new ArrayList<>(state.arrayCopy().rawEntries());
        Comparator<Map.Entry<Object, Object>> comparator;
        if (method.equals("uasort") || method.equals("uksort")) {
            PhpCallable callback = PhpCallable.resolve(ctx, arg, this);
            boolean keys = method.equals("uksort");
            comparator = (x, y) -> (int) PhpOps.toLong(callback.call(ctx, null, this,
                    keys ? x.getKey() : unwrap(x.getValue()), keys ? y.getKey() : unwrap(y.getValue())));
        } else {
            boolean keys = method.equals("ksort");
            long flag = arg == PhpNull.INSTANCE ? 0 : PhpOps.toLong(arg);
            comparator = (x, y) -> {
                Object left = keys ? x.getKey() : unwrap(x.getValue());
                Object right = keys ? y.getKey() : unwrap(y.getValue());
                if ((flag & 7) == 6 || method.equals("natsort") || method.equals("natcasesort")) {
                    return naturalCompare(PhpOps.toStr(left), PhpOps.toStr(right),
                            (flag & 8) != 0 || method.equals("natcasesort"));
                }
                if ((flag & 7) == 2) {
                    String a = PhpOps.toStr(left), b = PhpOps.toStr(right);
                    return (flag & 8) != 0 ? a.compareToIgnoreCase(b) : a.compareTo(b);
                }
                if ((flag & 7) == 1) { return Double.compare(PhpOps.toDouble(left), PhpOps.toDouble(right)); }
                return PhpOps.compare(left, right);
            };
        }
        entries.sort(comparator);
        PhpArray sorted = new PhpArray();
        for (var entry : entries) {
            if (entry.getValue() instanceof PhpReference ref) { sorted.putRef(entry.getKey(), ref); }
            else { sorted.put(entry.getKey(), PhpOps.copyOnAssign(entry.getValue())); }
        }
        state.storage.array = sorted;
        state.storage.object = null;
        state.storage.version++;
        state.storage.size = sorted.size();
        state.position = 0;
        return true;
    }

    private static Object unwrap(Object value) {
        return value instanceof PhpReference ref ? ref.getValue() : value;
    }

    private static int naturalCompare(String left, String right, boolean insensitive) {
        int i = 0, j = 0;
        while (i < left.length() && j < right.length()) {
            char a = left.charAt(i), b = right.charAt(j);
            if (Character.isDigit(a) && Character.isDigit(b)) {
                int ie = i, je = j;
                while (ie < left.length() && Character.isDigit(left.charAt(ie))) { ie++; }
                while (je < right.length() && Character.isDigit(right.charAt(je))) { je++; }
                String an = left.substring(i, ie), bn = right.substring(j, je);
                int comparison = a == '0' || b == '0' ? an.compareTo(bn)
                        : an.length() != bn.length() ? Integer.compare(an.length(), bn.length()) : an.compareTo(bn);
                if (comparison != 0) { return comparison; }
                i = ie; j = je;
                continue;
            }
            if (insensitive) { a = Character.toLowerCase(a); b = Character.toLowerCase(b); }
            if (a != b) { return Character.compare(a, b); }
            i++; j++;
        }
        return Integer.compare(left.length() - i, right.length() - j);
    }

    @Override
    public String getName() {
        return owner + "::" + method;
    }

    /** A builtin method is not guest code: tools treat it as internal. */
    @Override
    public boolean isInternal() {
        return true;
    }
}
