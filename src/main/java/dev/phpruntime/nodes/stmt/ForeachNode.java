package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.LoopNode;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.ControlFlow;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.SplInterfaces;
import dev.phpruntime.PhpLanguage;

import java.util.List;
import java.util.Map;

/** {@code foreach ($arr as $k => [$v | &$v | [...] ])}. */
public final class ForeachNode extends PhpNode {

    @Child private PhpNode subject;
    @Child private LValueNode subjectLValue;
    @Child private LValueNode keyTarget;
    @Child private LValueNode valueTarget;
    private final boolean byRef;
    @Child private PhpNode body;
    @Child private IteratorNode iterator;
    private final com.oracle.truffle.api.profiles.CountingConditionProfile iteratorProfile =
            com.oracle.truffle.api.profiles.CountingConditionProfile.create();
    private final com.oracle.truffle.api.profiles.CountingConditionProfile arrayProfile =
            com.oracle.truffle.api.profiles.CountingConditionProfile.create();

    public ForeachNode(PhpNode subject, LValueNode keyTarget, LValueNode valueTarget, PhpNode body) {
        this(subject, null, keyTarget, valueTarget, false, body);
    }

    public ForeachNode(PhpNode subject, LValueNode subjectLValue, LValueNode keyTarget, LValueNode valueTarget,
                        boolean byRef, PhpNode body) {
        this.subject = subject;
        this.subjectLValue = subjectLValue;
        this.keyTarget = keyTarget;
        this.valueTarget = valueTarget;
        this.byRef = byRef;
        this.body = body;
    }

    public ForeachNode(PhpLanguage language, PhpNode subject, LValueNode subjectLValue, LValueNode keyTarget,
                       LValueNode valueTarget, boolean byRef, PhpNode body) {
        this(subject, subjectLValue, keyTarget, valueTarget, byRef, body);
        this.iterator = new IteratorNode(language);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (byRef) {
            return executeByRef(frame);
        }
        Object s = subject.execute(frame);
        List<Map.Entry<Object, Object>> entries;
        boolean isArray = arrayProfile.profile(s instanceof PhpArray);
        if (isArray) {
            PhpArray arr = (PhpArray) s;
            return executeArray(frame, arr);
        } else if (s instanceof PhpObject obj) {
            if (iteratorProfile.profile(obj.phpClass().hasSplInterface(SplInterfaces.TRAVERSABLE))) {
                return executeIterator(frame, iterator.resolve(obj));
            }
            String caller = null;
            PhpFunctionRootNode fn = getRootNode() instanceof PhpFunctionRootNode function ? function
                    : getRootNode() instanceof dev.phpruntime.nodes.generator.GeneratorResumeRootNode root ? root.signature : null;
            if (fn != null) {
                if (fn.traitSelfSlot() >= 0 && frame.getObject(fn.traitSelfSlot()) instanceof PhpClass ts) {
                    caller = ts.name();
                } else {
                    caller = fn.declaringClass();
                }
            }
            entries = PhpClass.visibleEntries(PhpContext.get(this), obj, caller);
        } else {
            warnInvalidSubject(s);
            return null;
        }
        int count = 0;
        try {
            for (Map.Entry<Object, Object> e : entries) {
                TruffleSafepoint.poll(this);
                count++;
                if (keyTarget != null) {
                    keyTarget.write(frame, keyTarget.evalKeys(frame), entryKey(e));
                }
                Object rawVal = entryValue(e);
                Object curVal = rawVal instanceof PhpReference ref ? ref.getValue() : rawVal;
                valueTarget.write(frame, valueTarget.evalKeys(frame), PhpOps.copyOnAssign(curVal));
                try {
                    body.executeVoid(frame);
                } catch (ControlFlow.Break b) {
                    if (b.levels > 1) {
                        throw new ControlFlow.Break(b.levels - 1);
                    }
                    break;
                } catch (ControlFlow.Continue c) {
                    if (c.levels > 1) {
                        throw new ControlFlow.Continue(c.levels - 1);
                    }
                }
            }
        } finally {
            LoopNode.reportLoopCount(this, count);
        }
        return null;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object entryKey(Map.Entry<Object, ?> entry) {
        return entry.getKey();
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static <T> T entryValue(Map.Entry<Object, T> entry) {
        return entry.getValue();
    }

    private Object executeArray(VirtualFrame frame, PhpArray arr) {
        arr.share();
        int count = 0;
        try {
            for (int pos = arr.firstPos(); pos >= 0; pos = arr.nextPos(pos)) {
                TruffleSafepoint.poll(this);
                count++;
                if (keyTarget != null) {
                    keyTarget.write(frame, keyTarget.evalKeys(frame), arr.keyAt(pos));
                }
                Object rawVal = arr.rawValueAt(pos);
                Object curVal = rawVal instanceof PhpReference ref ? ref.getValue() : rawVal;
                valueTarget.write(frame, valueTarget.evalKeys(frame), PhpOps.copyOnAssign(curVal));
                try {
                    body.executeVoid(frame);
                } catch (ControlFlow.Break b) {
                    if (b.levels > 1) {
                        throw new ControlFlow.Break(b.levels - 1);
                    }
                    break;
                } catch (ControlFlow.Continue c) {
                    if (c.levels > 1) {
                        throw new ControlFlow.Continue(c.levels - 1);
                    }
                }
            }
        } finally {
            arr.releaseShare();
            LoopNode.reportLoopCount(this, count);
        }
        return null;
    }

    private Object executeByRef(VirtualFrame frame) {
        Object s;
        PhpReference subjectCell = null;
        if (subjectLValue != null) {
            Object[] sk = subjectLValue.evalKeys(frame);
            Object cur = subjectLValue.read(frame, sk);
            if (cur == null) {
                warnUndefinedSubject();
                cur = PhpNull.INSTANCE;
            }
            if (cur instanceof PhpArray arr) {
                subjectCell = subjectLValue.obtainCell(frame, sk);
                PhpArray writable = arr.forWrite();
                subjectCell.setValue(writable);
                s = writable;
            } else {
                s = cur;
            }
        } else {
            Object raw = subject.executeForRef(frame);
            if (raw instanceof PhpReference ref) {
                subjectCell = ref;
                if (ref.getValue() instanceof PhpArray arr) {
                    PhpArray writable = arr.forWrite();
                    ref.setValue(writable);
                    s = writable;
                } else {
                    s = ref.getValue() == null ? PhpNull.INSTANCE : ref.getValue();
                }
            } else if (raw instanceof PhpArray arr) {
                // A temporary (literal, call result): iterate a private copy, never an array
                // that other owners or other executions of a constant literal still see.
                s = arr.forWrite();
            } else {
                s = raw;
            }
        }

        if (s instanceof PhpArray arr) {
            List<Object> keys = arr.keys();
            int count = 0;
            try {
                for (Object k : keys) {
                    TruffleSafepoint.poll(this);
                    count++;
                    PhpArray currentArr = subjectCell != null && subjectCell.getValue() instanceof PhpArray ca ? ca : arr;
                    if (!currentArr.containsKey(k)) {
                        continue;
                    }
                    PhpReference elemCell = currentArr.obtainElementRef(k);
                    if (keyTarget != null) {
                        keyTarget.write(frame, keyTarget.evalKeys(frame), k);
                    }
                    valueTarget.bindCell(frame, valueTarget.evalKeys(frame), elemCell);
                    try {
                        body.executeVoid(frame);
                    } catch (ControlFlow.Break b) {
                        if (b.levels > 1) {
                            throw new ControlFlow.Break(b.levels - 1);
                        }
                        break;
                    } catch (ControlFlow.Continue c) {
                        if (c.levels > 1) {
                            throw new ControlFlow.Continue(c.levels - 1);
                        }
                    }
                }
            } finally {
                LoopNode.reportLoopCount(this, count);
            }
            return null;
        }
        if (s instanceof PhpObject obj) {
            if (obj.phpClass().hasSplInterface(SplInterfaces.TRAVERSABLE)) {
                com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                throw new PhpError("An iterator cannot be used with foreach by reference", this);
            }
            PhpFunctionRootNode fn = getRootNode() instanceof PhpFunctionRootNode function ? function
                    : getRootNode() instanceof dev.phpruntime.nodes.generator.GeneratorResumeRootNode root ? root.signature : null;
            String caller = fn != null ? fn.traitSelfSlot() >= 0 && frame.getObject(fn.traitSelfSlot()) instanceof PhpClass ts
                    ? ts.name() : fn.declaringClass() : null;
            List<Map.Entry<Object, PhpReference>> entries = PhpClass.visibleRefEntries(PhpContext.get(this), obj, caller);
            int count = 0;
            try {
                for (Map.Entry<Object, PhpReference> e : entries) {
                    TruffleSafepoint.poll(this);
                    count++;
                    if (keyTarget != null) {
                        keyTarget.write(frame, keyTarget.evalKeys(frame), entryKey(e));
                    }
                    valueTarget.bindCell(frame, valueTarget.evalKeys(frame), entryValue(e));
                    try {
                        body.executeVoid(frame);
                    } catch (ControlFlow.Break b) {
                        if (b.levels > 1) {
                            throw new ControlFlow.Break(b.levels - 1);
                        }
                        break;
                    } catch (ControlFlow.Continue c) {
                        if (c.levels > 1) {
                            throw new ControlFlow.Continue(c.levels - 1);
                        }
                    }
                }
            } finally {
                LoopNode.reportLoopCount(this, count);
            }
            return null;
        }
        warnInvalidSubject(s);
        return null;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private void warnInvalidSubject(Object subject) {
        PhpContext.get(this).warning("foreach() argument must be of type array|object, " + PhpOps.typeName(subject) + " given", line());
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private void warnUndefinedSubject() {
        PhpContext.get(this).warning("Undefined variable " + subjectLValue.describe(), line());
    }

    private Object executeIterator(VirtualFrame frame, PhpObject it) {
        iterator.rewind(it);
        int count = 0;
        try {
            while (iterator.valid(it)) {
                TruffleSafepoint.poll(this);
                count++;
                Object value = iterator.current(it);
                if (keyTarget != null) {
                    keyTarget.write(frame, keyTarget.evalKeys(frame), iterator.key(it));
                }
                valueTarget.write(frame, valueTarget.evalKeys(frame), PhpOps.copyOnAssign(value));
                try {
                    body.executeVoid(frame);
                } catch (ControlFlow.Break b) {
                    if (b.levels > 1) {
                        throw new ControlFlow.Break(b.levels - 1);
                    }
                    break;
                } catch (ControlFlow.Continue c) {
                    if (c.levels > 1) {
                        throw new ControlFlow.Continue(c.levels - 1);
                    }
                }
                iterator.next(it);
            }
        } finally {
            LoopNode.reportLoopCount(this, count);
        }
        return null;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        if (byRef) {
            emitByRef(e);
            return;
        }
        var b = e.builder();
        b.beginBlock();
        com.oracle.truffle.api.bytecode.BytecodeLocal cursor = e.temp();
        b.beginStoreLocal(cursor);
        b.beginForeachStart();
        e.expr(subject);
        b.endForeachStart();
        b.endStoreLocal();
        b.beginTryFinally(() -> {
            b.beginForeachEnd();
            b.emitLoadLocal(cursor);
            b.endForeachEnd();
        });
        b.beginBlock();
        com.oracle.truffle.api.bytecode.BytecodeLabel exit = b.createLabel();
        b.beginWhile();
        b.beginForeachNext();
        b.emitLoadLocal(cursor);
        b.endForeachNext();
        b.beginBlock();
        com.oracle.truffle.api.bytecode.BytecodeLabel next = b.createLabel();
        if (keyTarget != null) {
            e.assign(keyTarget, () -> {
                b.beginForeachKey();
                b.emitLoadLocal(cursor);
                b.endForeachKey();
            });
        }
        e.assign(valueTarget, () -> {
            b.beginForeachValue();
            b.emitLoadLocal(cursor);
            b.endForeachValue();
        });
        e.pushLoop(exit, next);
        e.stmt(body);
        e.popLoop();
        b.emitLabel(next);
        b.endBlock();
        b.endWhile();
        b.emitLabel(exit);
        b.endBlock();
        b.endTryFinally();
        b.endBlock();
    }

    private void emitByRef(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        b.beginBlock();
        com.oracle.truffle.api.bytecode.BytecodeLocal cursor = e.temp();
        b.beginStoreLocal(cursor);
        if (subjectLValue != null) {
            e.refStart(subjectLValue, subjectLValue.describe());
        } else {
            b.beginForeachRefStartValue(e.callerConstant());
            e.exprForRef(subject);
            e.traitSelfOperand();
            b.endForeachRefStartValue();
        }
        b.endStoreLocal();
        b.beginBlock();
        com.oracle.truffle.api.bytecode.BytecodeLabel exit = b.createLabel();
        b.beginWhile();
        b.beginForeachRefNext();
        b.emitLoadLocal(cursor);
        b.endForeachRefNext();
        b.beginBlock();
        com.oracle.truffle.api.bytecode.BytecodeLabel next = b.createLabel();
        if (keyTarget != null) {
            e.assign(keyTarget, () -> {
                b.beginForeachRefKey();
                b.emitLoadLocal(cursor);
                b.endForeachRefKey();
            });
        }
        e.bind(valueTarget, () -> {
            b.beginForeachRefCell();
            b.emitLoadLocal(cursor);
            b.endForeachRefCell();
        });
        e.pushLoop(exit, next);
        e.stmt(body);
        e.popLoop();
        b.emitLabel(next);
        b.endBlock();
        b.endWhile();
        b.emitLabel(exit);
        b.endBlock();
        b.endBlock();
    }
}
