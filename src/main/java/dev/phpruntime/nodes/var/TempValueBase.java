package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpNull;

/**
 * The base of an element write on a temporary value: {@code f()[] = $v}, {@code $o->m()['k'] = $v},
 * {@code (expr)[0] = $v}. php allows these: an ArrayAccess object receives offsetSet (the
 * write has its effect through the object), anything else is written into a copy that is
 * dropped. The bytecode operation evaluates {@link #expr()} once and passes the value as base
 * key 0; writes back to the base itself go nowhere, because the value is a temporary.
 */
public final class TempValueBase extends LValueNode {

    @Child private PhpNode expr;

    public TempValueBase(PhpNode expr) {
        this.expr = expr;
    }

    /** The expression producing the temporary (null in the bytecode template copy). */
    public PhpNode expr() {
        return expr;
    }

    /** The bytecode template: the value arrives as base key 0. */
    public TempValueBase headless() {
        return new TempValueBase(null);
    }

    @Override
    public Object read(VirtualFrame frame, Object[] keys) {
        return keys[0];
    }

    @Override
    public Object readIfSet(VirtualFrame frame, Object[] keys) {
        return keys[0] == PhpNull.INSTANCE ? null : keys[0];
    }

    @Override
    public void write(VirtualFrame frame, Object[] keys, Object value) {
        // A temporary: the updated copy is dropped, as in php.
    }

    @Override
    public void unset(VirtualFrame frame, Object[] keys) {
        // unset() on an element of a temporary is rejected at compile time by php; nothing to do.
    }

    @Override
    public String describe() {
        return "temporary value";
    }
}
