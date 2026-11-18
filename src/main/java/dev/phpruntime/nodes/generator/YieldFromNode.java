package dev.phpruntime.nodes.generator;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.ControlFlow;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.call.DispatchCallNode;
import dev.phpruntime.nodes.stmt.IteratorNode;
import dev.phpruntime.runtime.*;

import java.util.List;
import java.util.Map;

/** Delegation retains the source and its position; delegated keys do not advance outer auto keys. */
public final class YieldFromNode extends PhpNode {
    private static final class Cursor {
        List<Map.Entry<Object, Object>> entries;
        PhpObject iterator;
        int index;
        boolean suspended;
    }

    @Child private PhpNode source;
    @Child private IteratorNode protocol;
    @Child private DispatchCallNode resume = new DispatchCallNode();
    private final int generatorSlot;
    private final int stateSlot;

    public YieldFromNode(PhpLanguage language, int generatorSlot, int stateSlot, PhpNode source) {
        this.source = source;
        this.generatorSlot = generatorSlot;
        this.stateSlot = stateSlot;
        protocol = new IteratorNode(language);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        PhpGenerator outer = (PhpGenerator) frame.getObject(generatorSlot);
        Cursor cursor = (Cursor) frame.getObject(stateSlot);
        if (cursor == null) {
            Object value = source.execute(frame);
            cursor = new Cursor();
            if (value instanceof PhpArray array) {
                cursor.entries = array.entries();
            } else if (value instanceof PhpObject obj && SplInterfaces.has(obj, SplInterfaces.TRAVERSABLE)) {
                cursor.iterator = protocol.resolve(obj);
                if (!(cursor.iterator.nativeState() instanceof PhpGenerator)) {
                    protocol.rewind(cursor.iterator);
                }
            } else {
                com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                throw new PhpError("Can use \"yield from\" only with arrays and Traversables", this);
            }
            frame.setObject(stateSlot, cursor);
        }
        if (cursor.suspended) {
            cursor.suspended = false;
            if (cursor.iterator != null && cursor.iterator.nativeState() instanceof PhpGenerator inner) {
                Object input;
                try {
                    input = outer.takeInput();
                } catch (PhpException error) {
                    inner.throwInto(error.exceptionObject(), resume, this);
                    input = null;
                } catch (ControlFlow.GeneratorClose close) {
                    inner.close(resume, this);
                    throw close;
                }
                if (input != null) { inner.send(input, resume, this); }
            } else {
                outer.takeInput();
                cursor.index++;
                if (cursor.iterator != null) {
                    protocol.next(cursor.iterator);
                }
            }
        }
        boolean valid = cursor.iterator != null ? protocol.valid(cursor.iterator) : cursor.index < cursor.entries.size();
        if (!valid) {
            frame.setObject(stateSlot, null);
            return cursor.iterator != null && cursor.iterator.nativeState() instanceof PhpGenerator inner
                    ? inner.getReturn(this) : PhpNull.INSTANCE;
        }
        Object value = cursor.iterator != null ? protocol.current(cursor.iterator) : entryValue(cursor);
        Object key = cursor.iterator != null ? protocol.key(cursor.iterator) : entryKey(cursor);
        outer.checkYield(this);
        cursor.suspended = true;
        throw new ControlFlow.Yield(key, PhpOps.copyOnAssign(value));
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object entryKey(Cursor cursor) {
        return cursor.entries.get(cursor.index).getKey();
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object entryValue(Cursor cursor) {
        return cursor.entries.get(cursor.index).getValue();
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        com.oracle.truffle.api.bytecode.BytecodeLocal gen = e.generatorLocal();
        com.oracle.truffle.api.bytecode.BytecodeLocal cursor = e.temp();
        b.beginBlock();
        b.beginStoreLocal(cursor);
        b.beginYieldFromStart();
        e.expr(source);
        b.endYieldFromStart();
        b.endStoreLocal();
        b.beginWhile();
        b.beginYieldFromValid();
        b.emitLoadLocal(cursor);
        b.endYieldFromValid();
        b.beginYieldFromResume();
        b.emitLoadLocal(gen);
        b.emitLoadLocal(cursor);
        b.beginYield();
        b.beginYieldFromSuspend();
        b.emitLoadLocal(gen);
        b.emitLoadLocal(cursor);
        b.endYieldFromSuspend();
        b.endYield();
        b.endYieldFromResume();
        b.endWhile();
        b.beginYieldFromResult();
        b.emitLoadLocal(cursor);
        b.endYieldFromResult();
        b.endBlock();
    }
}
