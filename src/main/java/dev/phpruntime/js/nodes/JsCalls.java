package dev.phpruntime.js.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import dev.phpruntime.js.JsContext;
import dev.phpruntime.js.runtime.JsError;
import dev.phpruntime.js.runtime.JsOps;
import dev.phpruntime.js.runtime.JsTypes;
import dev.phpruntime.js.runtime.JsValues.JsBuiltin;
import dev.phpruntime.js.runtime.JsValues.JsFunction;
import dev.phpruntime.js.runtime.JsValues.JsObject;
import dev.phpruntime.js.runtime.JsValues.JsPromise;
import dev.phpruntime.js.runtime.JsValues.Undefined;

/** Calls, function values, object literals, template strings and await. */
public final class JsCalls {

    private JsCalls() {
    }

    /**
     * Invokes a callee. Up to three distinct user functions per call site get a
     * DirectCallNode each, so Graal can inline them; recursion such as tak() or fib()
     * becomes a direct, inlinable call.
     */
    @TypeSystemReference(JsTypes.class)
    public abstract static class Dispatch extends Node {
        public abstract Object execute(Object callee, Object[] args);

        @Specialization(guards = "fn.target == cachedTarget", limit = "3")
        protected static Object doDirect(JsFunction fn, Object[] args,
                                         @Cached("fn.target") RootCallTarget cachedTarget,
                                         @Cached("create(cachedTarget)") DirectCallNode call) {
            args[0] = fn.scope;
            return call.call(args);
        }

        @Specialization(replaces = "doDirect")
        protected static Object doIndirect(JsFunction fn, Object[] args, @Cached IndirectCallNode call) {
            args[0] = fn.scope;
            return call.call(fn.target, args);
        }

        @Specialization
        protected static Object doBuiltin(JsBuiltin fn, Object[] args) {
            return fn.call(args);
        }

        @Fallback
        @TruffleBoundary
        protected Object doNotCallable(Object callee, Object[] args) {
            throw JsError.type(JsOps.toStr(callee) + " is not a function", this);
        }
    }

    /** {@code f(a, b)} and {@code obj.m(a, b)}; the latter passes obj as {@code this}. */
    public static final class Call extends JsNode {
        @Child private JsNode callee;       // plain call
        @Child private JsNode receiver;     // method call
        private final String method;
        @Children private final JsNode[] arguments;
        @Child private Dispatch dispatch = JsCallsFactory.DispatchNodeGen.create();

        public Call(JsNode callee, JsNode receiver, String method, JsNode[] arguments) {
            this.callee = callee;
            this.receiver = receiver;
            this.method = method;
            this.arguments = arguments;
        }

        @Override
        @ExplodeLoop
        public Object executeGeneric(VirtualFrame frame) {
            Object thisValue;
            Object fn;
            if (receiver != null) {
                thisValue = receiver.executeGeneric(frame);
                fn = JsOps.getProperty(thisValue, method, this);
            } else {
                thisValue = Undefined.INSTANCE;
                fn = callee.executeGeneric(frame);
            }
            Object[] args = new Object[arguments.length + 2];
            args[1] = thisValue;
            for (int i = 0; i < arguments.length; i++) {
                args[i + 2] = arguments[i].executeGeneric(frame);
            }
            return dispatch.execute(fn, args);
        }
    }

    /** A function or arrow expression; evaluates to a closure over the module scope. */
    public static final class FunctionLiteral extends JsNode {
        private final RootCallTarget target;
        private final String name;
        private final boolean inModule;

        public FunctionLiteral(RootNode root, String name, boolean inModule) {
            this.target = root.getCallTarget();
            this.name = name;
            this.inModule = inModule;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            MaterializedFrame scope = inModule ? frame.materialize() : (MaterializedFrame) frame.getArguments()[0];
            return new JsFunction(target, scope, name);
        }
    }

    public static final class ObjectLiteral extends JsNode {
        private final String[] keys;
        @Children private final JsNode[] values;

        public ObjectLiteral(String[] keys, JsNode[] values) {
            this.keys = keys;
            this.values = values;
        }

        @Override
        @ExplodeLoop
        public Object executeGeneric(VirtualFrame frame) {
            JsObject o = new JsObject();
            for (int i = 0; i < keys.length; i++) {
                o.put(keys[i], values[i].executeGeneric(frame));
            }
            return o;
        }
    }

    /** Template literal: alternating string pieces and expressions, joined with ToString. */
    public static final class Template extends JsNode {
        @Children private final JsNode[] parts;

        public Template(JsNode[] parts) {
            this.parts = parts;
        }

        @Override
        @ExplodeLoop
        public Object executeGeneric(VirtualFrame frame) {
            Object[] values = new Object[parts.length];
            for (int i = 0; i < parts.length; i++) {
                values[i] = parts[i].executeGeneric(frame);
            }
            return join(values);
        }

        @TruffleBoundary
        private static String join(Object[] values) {
            StringBuilder sb = new StringBuilder();
            for (Object v : values) {
                sb.append(JsOps.toStr(v));
            }
            return sb.toString();
        }
    }

    /**
     * {@code await x}. A request has its own thread, so awaiting a host promise blocks
     * that thread; the scheduler hands its CPU slot to other requests meanwhile.
     */
    public static final class Await extends JsNode {
        @Child private JsNode operand;

        public Await(JsNode operand) {
            this.operand = operand;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object v = operand.executeGeneric(frame);
            if (v instanceof JsPromise p) {
                return settle(p);
            }
            return v;
        }

        @TruffleBoundary
        private Object settle(JsPromise p) {
            long wait = p.readyAtNanos - System.nanoTime();
            if (wait > 0) {
                JsContext.get(this).request().scheduler.blocking(this, () -> Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000)));
            }
            return p.value;
        }
    }
}
