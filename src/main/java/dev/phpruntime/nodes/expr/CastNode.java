package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.object.DynamicObject;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.Visibility;

import java.util.Map;

/** {@code (int)}, {@code (float)}, {@code (string)}, {@code (bool)}, {@code (array)}, {@code (object)}. */
public final class CastNode extends PhpNode {

    public enum Kind { INT, FLOAT, STRING, BOOL, ARRAY, OBJECT }

    @Child private PhpNode operand;
    private final Kind kind;

    public CastNode(Kind kind, PhpNode operand) {
        this.kind = kind;
        this.operand = operand;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object v = operand.execute(frame);
        return switch (kind) {
            case INT -> PhpOps.toLong(v);
            case FLOAT -> PhpOps.toDouble(v);
            case STRING -> {
                try {
                    yield PhpOps.toStr(v);
                } catch (dev.phpruntime.runtime.PhpError e) {
                    throw e.at(this);
                }
            }
            case BOOL -> PhpOps.toBool(v);
            case ARRAY -> toArray(v);
            case OBJECT -> toObject(PhpContext.get(this), v);
        };
    }

    @TruffleBoundary
    private static Object toArray(Object v) {
        if (v instanceof PhpArray) {
            return v;
        }
        PhpArray a = new PhpArray();
        if (v instanceof PhpObject obj) {
            if (obj.nativeState() instanceof dev.phpruntime.runtime.SplArrayState state
                    && (state.flags & dev.phpruntime.runtime.SplArrayState.STD_PROP_LIST) == 0) {
                return state.arrayCopy();
            }
            PhpClass cls = obj.phpClass();
            Object[] keys = DynamicObject.GetKeyArrayNode.getUncached().execute(obj);
            DynamicObject.GetNode get = DynamicObject.GetNode.getUncached();
            for (Object rawKey : keys) {
                String k = (String) rawKey;
                Object rawVal = get.execute(obj, k, PhpNull.INSTANCE);
                if (rawVal == PhpClass.UNINITIALIZED) {
                    continue;
                }
                String arrKey = k;
                if (!k.startsWith("\0")) {
                    PhpClass.InstanceProp p = cls.findVisibleProp(k);
                    if (p != null && p.visibility() == Visibility.PROTECTED) {
                        arrKey = "\0*\0" + k;
                    }
                }
                a.put(arrKey, PhpOps.copyOnAssign(PhpClass.unwrapProp(rawVal)));
            }
            if (obj.nativeState() != null && !(obj.nativeState() instanceof dev.phpruntime.runtime.SplArrayState)) {
                Map<String, Object> extra = obj.nativeState().debugProperties(obj);
                if (extra != null) {
                    for (Map.Entry<String, Object> e : extra.entrySet()) {
                        a.put(e.getKey(), e.getValue());
                    }
                }
            }
            return a;
        }
        if (v != PhpNull.INSTANCE) {
            a.append(v);
        }
        return a;
    }

    @TruffleBoundary
    private static PhpObject toObject(PhpContext ctx, Object v) {
        if (v instanceof PhpObject obj) {
            return obj;
        }
        PhpObject obj = new PhpObject(ctx.stdClass(), ctx.nextObjectId());
        DynamicObject.PutNode put = DynamicObject.PutNode.getUncached();
        if (v instanceof PhpArray arr) {
            for (Map.Entry<Object, Object> e : arr.entries()) {
                put.execute(obj, PhpOps.toStr(e.getKey()), PhpOps.copyOnAssign(e.getValue()));
            }
        } else if (v != PhpNull.INSTANCE) {
            put.execute(obj, "scalar", v);
        }
        return obj;
    }
}
