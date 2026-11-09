package dev.phpruntime.js;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.js.runtime.JsOps;
import dev.phpruntime.js.runtime.JsValues.JsBuiltin;
import dev.phpruntime.js.runtime.JsValues.JsObject;
import dev.phpruntime.js.runtime.JsValues.JsPromise;
import dev.phpruntime.js.runtime.JsValues.Undefined;
import dev.phpruntime.runtime.PhpRequest;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-request state for the JavaScript front end: the global builtins, the module
 * object and the output stream. One context per request, as on the PHP side.
 */
public final class JsContext {

    private static final ContextReference<JsContext> REFERENCE = ContextReference.create(JsLanguage.class);

    /** Globals visible to every module, in the order the module root initializes them. */
    public static final List<String> GLOBALS = List.of(
            "console", "Math", "Number", "String", "parseInt", "performance", "Date",
            "require", "module", "exports", "NaN", "Infinity", "undefined");

    private final TruffleLanguage.Env env;
    private final long startNanos = System.nanoTime();
    private final Map<String, Object> globals = new HashMap<>();
    private final JsObject module = new JsObject();
    private PhpRequest request;

    JsContext(TruffleLanguage.Env env) {
        this.env = env;
        JsObject exports = new JsObject();
        module.put("exports", exports);
        globals.put("module", module);
        globals.put("exports", exports);
        globals.put("console", object("log", new JsBuiltin("log", (self, a) -> {
            log(a);
            return Undefined.INSTANCE;
        })));
        globals.put("Math", math());
        globals.put("Number", new JsBuiltin("Number", (self, a) -> JsBuiltin.argCount(a) == 0 ? (Object) 0 : JsOps.toNumber(JsBuiltin.arg(a, 0))));
        globals.put("String", new JsBuiltin("String", (self, a) -> JsBuiltin.argCount(a) == 0 ? "" : JsOps.toStr(JsBuiltin.arg(a, 0))));
        globals.put("parseInt", new JsBuiltin("parseInt", (self, a) -> parseInt(JsOps.toStr(JsBuiltin.arg(a, 0)), JsBuiltin.arg(a, 1))));
        globals.put("performance", object("now", new JsBuiltin("now", (self, a) -> (System.nanoTime() - startNanos) / 1e6)));
        globals.put("Date", object("now", new JsBuiltin("now", (self, a) -> JsOps.normalize((double) System.currentTimeMillis()))));
        globals.put("require", new JsBuiltin("require", (self, a) -> require(JsOps.toStr(JsBuiltin.arg(a, 0)))));
        globals.put("NaN", Double.NaN);
        globals.put("Infinity", Double.POSITIVE_INFINITY);
        globals.put("undefined", Undefined.INSTANCE);
    }

    public static JsContext get(Node node) {
        return REFERENCE.get(node);
    }

    public TruffleLanguage.Env env() {
        return env;
    }

    public PhpRequest request() {
        if (request == null) {
            request = PhpRequest.current();
        }
        return request;
    }

    public JsObject module() {
        return module;
    }

    @TruffleBoundary
    public Object global(String name) {
        return globals.get(name);
    }

    /** Response body bytes (HTTP) or standard output (CLI). */
    @TruffleBoundary
    public void write(String s) {
        try {
            env.out().write(s.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // client went away
        }
    }

    @TruffleBoundary
    private void log(Object[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < JsBuiltin.argCount(args); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(JsOps.inspect(JsBuiltin.arg(args, i), false));
        }
        sb.append('\n');
        // Under HTTP, console output goes to the server log as in Node; the body is res.write/end.
        OutputStream target = request().http ? env.err() : env.out();
        try {
            target.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // ignore
        }
    }

    private static JsObject object(String key, Object value) {
        JsObject o = new JsObject();
        o.put(key, value);
        return o;
    }

    private static JsObject math() {
        JsObject m = new JsObject();
        m.put("floor", new JsBuiltin("floor", (self, a) -> JsOps.normalize(Math.floor(JsOps.toDouble(JsBuiltin.arg(a, 0))))));
        m.put("ceil", new JsBuiltin("ceil", (self, a) -> JsOps.normalize(Math.ceil(JsOps.toDouble(JsBuiltin.arg(a, 0))))));
        m.put("trunc", new JsBuiltin("trunc", (self, a) -> {
            double d = JsOps.toDouble(JsBuiltin.arg(a, 0));
            return JsOps.normalize(d < 0 ? Math.ceil(d) : Math.floor(d));
        }));
        m.put("round", new JsBuiltin("round", (self, a) -> JsOps.normalize(Math.floor(JsOps.toDouble(JsBuiltin.arg(a, 0)) + 0.5))));
        m.put("sqrt", new JsBuiltin("sqrt", (self, a) -> JsOps.normalize(Math.sqrt(JsOps.toDouble(JsBuiltin.arg(a, 0))))));
        m.put("abs", new JsBuiltin("abs", (self, a) -> JsOps.normalize(Math.abs(JsOps.toDouble(JsBuiltin.arg(a, 0))))));
        m.put("min", new JsBuiltin("min", (self, a) -> minMax(a, true)));
        m.put("max", new JsBuiltin("max", (self, a) -> minMax(a, false)));
        m.put("random", new JsBuiltin("random", (self, a) -> Math.random()));
        m.put("PI", Math.PI);
        return m;
    }

    private static Object minMax(Object[] a, boolean min) {
        double r = min ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        for (int i = 0; i < JsBuiltin.argCount(a); i++) {
            double d = JsOps.toDouble(JsBuiltin.arg(a, i));
            if (Double.isNaN(d)) {
                return Double.NaN;
            }
            r = min ? Math.min(r, d) : Math.max(r, d);
        }
        return JsOps.normalize(r);
    }

    private static Object parseInt(String s, Object radixArg) {
        int radix = radixArg == Undefined.INSTANCE ? 10 : (int) JsOps.toDouble(radixArg);
        String t = s.strip();
        int i = 0;
        boolean negative = false;
        if (i < t.length() && (t.charAt(i) == '+' || t.charAt(i) == '-')) {
            negative = t.charAt(i) == '-';
            i++;
        }
        int start = i;
        while (i < t.length() && Character.digit(t.charAt(i), radix) >= 0) {
            i++;
        }
        if (i == start) {
            return Double.NaN;
        }
        double v = new java.math.BigInteger(t.substring(start, i), radix).doubleValue();
        return JsOps.normalize(negative ? -v : v);
    }

    private Object require(String name) {
        switch (name) {
            case "node:timers/promises", "timers/promises" -> {
                JsObject timers = new JsObject();
                timers.put("setTimeout", new JsBuiltin("setTimeout", (self, a) -> {
                    double ms = JsOps.toDouble(JsBuiltin.arg(a, 0));
                    long delay = Double.isNaN(ms) || ms < 0 ? 0 : (long) (ms * 1_000_000);
                    return new JsPromise(System.nanoTime() + delay, JsBuiltin.arg(a, 1));
                }));
                return timers;
            }
            default -> throw new IllegalArgumentException("Cannot find module '" + name + "'");
        }
    }
}
