package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;
import dev.phpruntime.runtime.PhpObject;

/**
 * {@code list($a, $b)} or {@code [$a, 'k' => &$b, [, $c]]} destructuring target.
 * Works both on the left side of {@code =} (via {@link #assignFrom}) and as a
 * {@code foreach} value target (via {@link #write} / {@link #bindCell}).
 */
public final class DestructureLValueNode extends LValueNode {

    @Children private final PhpNode[] keyNodes;
    @Children private final LValueNode[] targetNodes;
    @CompilationFinal(dimensions = 1) private final long[] autoIndices;
    @CompilationFinal(dimensions = 1) private final boolean[] byRef;
    private final boolean hasAnyRef;
    @Child private ArrayAccessNode access;

    public DestructureLValueNode(PhpNode[] keyNodes, LValueNode[] targetNodes, long[] autoIndices, boolean[] byRef) {
        this.keyNodes = keyNodes;
        this.targetNodes = targetNodes;
        this.autoIndices = autoIndices;
        this.byRef = byRef;
        boolean anyRef = false;
        for (int i = 0; i < targetNodes.length; i++) {
            if (byRef[i] || (targetNodes[i] instanceof DestructureLValueNode sub && sub.hasAnyRef())) {
                anyRef = true;
                break;
            }
        }
        this.hasAnyRef = anyRef;
    }

    public DestructureLValueNode(PhpLanguage language, PhpNode[] keys, LValueNode[] targets, long[] indices, boolean[] refs) {
        this(keys, targets, indices, refs);
        access = new ArrayAccessNode(language);
    }

    public boolean hasAnyRef() {
        return hasAnyRef;
    }

    public Object assignFrom(VirtualFrame frame, PhpNode rhsExpr, LValueNode rhsLValue) {
        if (hasAnyRef) {
            if (rhsLValue != null) {
                Object[] rk = rhsLValue.evalKeys(frame);
                Object cur = rhsLValue.read(frame, rk);
                if (cur == null) {
                    PhpContext.get(this).warning("Undefined variable " + rhsLValue.describe(), line());
                    cur = PhpNull.INSTANCE;
                }
                if (cur instanceof PhpArray arr) {
                    PhpReference rootCell = rhsLValue.obtainCell(frame, rk);
                    PhpArray writable = arr.forWrite();
                    rootCell.setValue(writable);
                    destructureRef(frame, rootCell);
                    return PhpOps.copyOnAssign(rootCell.getValue());
                }
                destructureValue(frame, cur);
                return cur;
            }
            Object raw = rhsExpr.executeForRef(frame);
            if (raw instanceof PhpReference ref) {
                if (ref.getValue() instanceof PhpArray arr) {
                    ref.setValue(arr.forWrite());
                    destructureRef(frame, ref);
                    return PhpOps.copyOnAssign(ref.getValue());
                }
                Object v = ref.getValue() == null ? PhpNull.INSTANCE : ref.getValue();
                destructureValue(frame, v);
                return v;
            }
            PhpContext.get(this).notice("Attempting to set reference to non referenceable value", line());
            destructureValue(frame, raw);
            return PhpOps.copyOnAssign(raw);
        }
        Object rhs = rhsExpr.execute(frame);
        destructureValue(frame, rhs);
        return rhs;
    }

    @Override
    public Object read(VirtualFrame frame, Object[] keys) {
        throw new PhpError("Cannot use list() / array destructuring in read context", this);
    }

    @Override
    public void write(VirtualFrame frame, Object[] keys, Object value) {
        destructureValue(frame, value);
    }

    @Override
    public void bindCell(VirtualFrame frame, Object[] keys, PhpReference cell) {
        if (cell.getValue() instanceof PhpArray arr) {
            cell.setValue(arr.forWrite());
            destructureRef(frame, cell);
        } else {
            destructureValue(frame, cell.getValue() == null ? PhpNull.INSTANCE : cell.getValue());
        }
    }

    @ExplodeLoop
    private void destructureValue(VirtualFrame frame, Object source) {
        PhpArray srcArr = source instanceof PhpArray a ? a : null;
        for (int i = 0; i < targetNodes.length; i++) {
            LValueNode target = targetNodes[i];
            if (target == null) {
                if (keyNodes[i] != null) {
                    keyNodes[i].execute(frame);
                }
                continue;
            }
            Object k = keyNodes[i] != null ? keyNodes[i].execute(frame) : autoIndices[i];
            Object[] tKeys = target.evalKeys(frame);
            Object elemVal;
            if (srcArr != null) {
                Object fetched = fetchElement(srcArr, k);
                if (fetched == null) {
                    warnMissingKey(k);
                    elemVal = PhpNull.INSTANCE;
                } else {
                    elemVal = PhpOps.copyOnAssign(fetched);
                }
            } else if (source instanceof PhpObject obj) {
                Object fetched = byRef[i] ? access.cell(obj, k) : access.get(obj, k);
                if (fetched instanceof PhpReference cell) {
                    target.bindCell(frame, tKeys, cell);
                    continue;
                }
                elemVal = PhpOps.copyOnAssign(fetched);
            } else {
                elemVal = PhpNull.INSTANCE;
            }
            if (byRef[i]) {
                target.bindCell(frame, tKeys, new PhpReference(elemVal));
            } else {
                target.write(frame, tKeys, elemVal);
            }
        }
    }

    @ExplodeLoop
    private void destructureRef(VirtualFrame frame, PhpReference containerCell) {
        for (int i = 0; i < targetNodes.length; i++) {
            LValueNode target = targetNodes[i];
            if (target == null) {
                if (keyNodes[i] != null) {
                    keyNodes[i].execute(frame);
                }
                continue;
            }
            Object k = keyNodes[i] != null ? keyNodes[i].execute(frame) : autoIndices[i];
            Object[] tKeys = target.evalKeys(frame);
            PhpArray curArr = containerCell.getValue() instanceof PhpArray a ? a : null;
            if (curArr == null) {
                if (byRef[i]) {
                    target.bindCell(frame, tKeys, new PhpReference(PhpNull.INSTANCE));
                } else {
                    target.write(frame, tKeys, PhpNull.INSTANCE);
                }
                continue;
            }
            if (byRef[i]) {
                PhpReference elemCell = obtainElementCell(curArr, k);
                target.bindCell(frame, tKeys, elemCell);
            } else if (target instanceof DestructureLValueNode nested && nested.hasAnyRef()) {
                if (!hasElement(curArr, k)) {
                    warnMissingKey(k);
                    nested.destructureValue(frame, PhpNull.INSTANCE);
                } else {
                    PhpReference subCell = obtainElementCell(curArr, k);
                    nested.bindCell(frame, NO_KEYS, subCell);
                }
            } else {
                Object fetched = fetchElement(curArr, k);
                if (fetched == null) {
                    warnMissingKey(k);
                    target.write(frame, tKeys, PhpNull.INSTANCE);
                } else {
                    target.write(frame, tKeys, PhpOps.copyOnAssign(fetched));
                }
            }
        }
    }

    @TruffleBoundary
    private Object fetchElement(PhpArray arr, Object k) {
        try {
            return arr.get(k);
        } catch (PhpError e) {
            throw e.at(this);
        }
    }

    @TruffleBoundary
    private boolean hasElement(PhpArray arr, Object k) {
        try {
            return arr.containsKey(k);
        } catch (PhpError e) {
            throw e.at(this);
        }
    }

    @TruffleBoundary
    private PhpReference obtainElementCell(PhpArray arr, Object k) {
        try {
            return arr.obtainElementRef(k);
        } catch (PhpError e) {
            throw e.at(this);
        }
    }

    @TruffleBoundary
    private void warnMissingKey(Object rawKey) {
        Object norm = PhpArray.normalizeKey(rawKey);
        String shown = norm instanceof String s ? '"' + s + '"' : PhpOps.toStr(norm);
        PhpContext.get(this).warning("Undefined array key " + shown, line());
    }

    @Override
    public void unset(VirtualFrame frame, Object[] keys) {
        throw new PhpError("Cannot unset destructuring pattern", this);
    }

    @Override
    public String describe() {
        return "[...]";
    }
}
