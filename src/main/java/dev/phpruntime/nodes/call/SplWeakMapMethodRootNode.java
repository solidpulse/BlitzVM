package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpStrings;
import dev.phpruntime.runtime.WeakMapIteratorState;
import dev.phpruntime.runtime.WeakMapState;

/** WeakMap: object keys held weakly, values strongly, iteration in insertion order. */
public final class SplWeakMapMethodRootNode extends RootNode {
    private final String name;

    public SplWeakMapMethodRootNode(PhpLanguage language, String name) {
        super(language);
        this.name = name;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (!PhpStrings.CHECK) {
            return invoke(frame.getArguments());
        }
        PhpStrings.checkArguments(frame.getArguments(), "WeakMap::" + name);
        Object result = invoke(frame.getArguments());
        return PhpStrings.CHECK ? PhpStrings.checkReturn(result, "WeakMap::" + name) : result;
    }

    @TruffleBoundary
    private Object invoke(Object[] args) {
        PhpObject self = (PhpObject) args[0];
        int count = args.length - 2;
        String signature = switch (name) {
            case "offsetSet" -> "2";
            case "count", "getIterator" -> "0";
            default -> "1";
        };
        if (!signature.equals(Integer.toString(count))) {
            throw new PhpError(PhpError.ARGUMENT_COUNT, "WeakMap::" + name + "() expects exactly "
                    + (signature.equals("1") ? "1 argument" : signature + " arguments") + ", " + count + " given", this);
        }
        if (self.nativeState() == null) {
            self.setNativeState(new WeakMapState());
        }
        WeakMapState state = (WeakMapState) self.nativeState();
        return switch (name) {
            case "offsetSet" -> {
                state.put(key(args[2]), args[3]);
                yield PhpNull.INSTANCE;
            }
            case "offsetExists" -> state.exists(key(args[2]));
            case "offsetGet" -> {
                Object value = state.get(key(args[2]));
                if (value == null) {
                    throw new PhpError("Object " + args[2] + " not contained in WeakMap", this);
                }
                yield value;
            }
            case "offsetUnset" -> {
                state.remove(key(args[2]));
                yield PhpNull.INSTANCE;
            }
            case "count" -> (long) state.size();
            case "getIterator" -> {
                PhpContext ctx = PhpContext.get(this);
                PhpClass cls = ctx.lookupClass("InternalIterator");
                PhpObject iterator = new PhpObject(cls, ctx.nextObjectId());
                cls.populateDefaults(iterator);
                iterator.setNativeState(new WeakMapIteratorState(state));
                yield iterator;
            }
            default -> throw new IllegalStateException("WeakMap::" + name);
        };
    }

    private PhpObject key(Object value) {
        if (value instanceof PhpObject object) {
            return object;
        }
        throw new PhpError(PhpError.TYPE_ERROR, "WeakMap key must be an object", this);
    }

    @Override
    public String getName() {
        return "WeakMap::" + name;
    }

    /** A builtin method is not guest code: tools treat it as internal. */
    @Override
    public boolean isInternal() {
        return true;
    }
}
