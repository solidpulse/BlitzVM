package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpReference;

/**
 * A variable in the global symbol table: top-level script variables and superglobals.
 * Indexed by dense {@link dev.phpruntime.PhpLanguage#globalId} with no hash lookup.
 * {@code $GLOBALS} (id &lt; 0) returns a read-only snapshot on read and forbids whole-variable writes.
 */
public final class GlobalVarNode extends LValueNode {

    private final String name;
    private final int globalId;
    private final boolean lazySuperglobal;

    public GlobalVarNode(String name, int globalId) {
        this.name = name;
        this.globalId = globalId;
        lazySuperglobal = name.equals("_ENV") || name.equals("_REQUEST");
    }

    public String name() {
        return name;
    }

    public int globalId() {
        return globalId;
    }

    public boolean isGlobalsSuperglobal() {
        return globalId < 0;
    }

    @Override
    public Object read(VirtualFrame frame, Object[] keys) {
        PhpContext ctx = PhpContext.get(this);
        if (lazySuperglobal) { ctx.exposeAutoGlobalById(globalId); }
        if (globalId < 0) {
            return ctx.buildGlobalsSnapshot();
        }
        return ctx.readGlobalById(globalId);
    }

    @Override
    public void write(VirtualFrame frame, Object[] keys, Object value) {
        if (globalId < 0) {
            throw PhpError.fatal("$GLOBALS can only be modified using the $GLOBALS[$name] = $value syntax").at(this);
        }
        PhpContext ctx = PhpContext.get(this);
        if (lazySuperglobal) { ctx.exposeAutoGlobalById(globalId); }
        ctx.writeGlobalById(globalId, value);
    }

    @Override
    public PhpReference obtainCell(VirtualFrame frame, Object[] keys) {
        if (globalId < 0) {
            throw PhpError.fatal("Cannot acquire reference to $GLOBALS").at(this);
        }
        PhpContext ctx = PhpContext.get(this);
        if (lazySuperglobal) { ctx.exposeAutoGlobalById(globalId); }
        return ctx.globalRefById(globalId);
    }

    @Override
    public String appendString(VirtualFrame frame, Object[] keys, String suffix, boolean resultNeeded) {
        if (globalId < 0) { return super.appendString(frame, keys, suffix, resultNeeded); }
        PhpContext ctx = PhpContext.get(this);
        if (lazySuperglobal) { ctx.exposeAutoGlobalById(globalId); }
        return ctx.appendGlobalById(globalId, suffix, resultNeeded);
    }

    @Override
    public void bindCell(VirtualFrame frame, Object[] keys, PhpReference cell) {
        if (globalId < 0) {
            throw PhpError.fatal("$GLOBALS can only be modified using the $GLOBALS[$name] = $value syntax").at(this);
        }
        PhpContext ctx = PhpContext.get(this);
        if (lazySuperglobal) { ctx.exposeAutoGlobalById(globalId); }
        ctx.bindGlobalById(globalId, cell);
    }

    @Override
    public void unset(VirtualFrame frame, Object[] keys) {
        if (globalId < 0) {
            throw PhpError.fatal("$GLOBALS can only be modified using the $GLOBALS[$name] = $value syntax").at(this);
        }
        PhpContext.get(this).unsetGlobalById(globalId);
    }

    @Override
    public String describe() {
        return "$" + name;
    }

    public GlobalVarNode headless() {
        return new GlobalVarNode(name, globalId).at(line());
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.place(this, dev.phpruntime.bytecode.PhpBytecodeRootNode.P_READ, 0, null);
    }
}
