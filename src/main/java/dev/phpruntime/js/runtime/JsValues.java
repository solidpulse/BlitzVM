package dev.phpruntime.js.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.MaterializedFrame;

import java.util.LinkedHashMap;
import java.util.Map;

/** The JavaScript value classes that are not Java primitives or strings. */
public final class JsValues {

    private JsValues() {
    }

    /** {@code undefined}. */
    public static final class Undefined {
        public static final Undefined INSTANCE = new Undefined();

        private Undefined() {
        }

        @Override
        public String toString() {
            return "undefined";
        }
    }

    /** {@code null}. */
    public static final class Null {
        public static final Null INSTANCE = new Null();

        private Null() {
        }

        @Override
        public String toString() {
            return "null";
        }
    }

    /** A plain object: insertion-ordered string keys. */
    public static final class JsObject {
        private final Map<String, Object> props = new LinkedHashMap<>();

        @TruffleBoundary
        public JsObject() {
        }

        @TruffleBoundary
        public Object get(String key) {
            Object v = props.get(key);
            return v == null ? Undefined.INSTANCE : v;
        }

        @TruffleBoundary
        public void put(String key, Object value) {
            props.put(key, value);
        }

        @TruffleBoundary
        public Map<String, Object> entries() {
            return props;
        }
    }

    /**
     * A user function. {@code scope} is the module frame, which inner functions read
     * top-level bindings from; it is passed as argument 0 on every call.
     */
    public static final class JsFunction {
        public final RootCallTarget target;
        public final MaterializedFrame scope;
        public final String name;

        public JsFunction(RootCallTarget target, MaterializedFrame scope, String name) {
            this.target = target;
            this.scope = scope;
            this.name = name;
        }
    }

    /** A function implemented in Java. Arguments start at index 2 of the call array; index 1 is {@code this}. */
    public static final class JsBuiltin {
        @FunctionalInterface
        public interface Impl {
            Object call(Object thisValue, Object[] args);
        }

        public final String name;
        private final Impl impl;

        public JsBuiltin(String name, Impl impl) {
            this.name = name;
            this.impl = impl;
        }

        @TruffleBoundary
        public Object call(Object[] frameArgs) {
            return impl.call(frameArgs[1], frameArgs);
        }

        public static Object arg(Object[] frameArgs, int i) {
            return i + 2 < frameArgs.length ? frameArgs[i + 2] : Undefined.INSTANCE;
        }

        public static int argCount(Object[] frameArgs) {
            return frameArgs.length - 2;
        }
    }

    /**
     * A promise from a host API such as {@code timers.setTimeout}. There is no event
     * loop: each request has its own thread, so {@code await} simply blocks that thread
     * (handing its CPU slot to other requests) until the value is ready.
     */
    public static final class JsPromise {
        public final long readyAtNanos;
        public final Object value;

        public JsPromise(long readyAtNanos, Object value) {
            this.readyAtNanos = readyAtNanos;
            this.value = value;
        }
    }
}
