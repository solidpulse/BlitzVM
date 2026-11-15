package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpContext.OutputBuffer;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;

import java.util.List;

/**
 * Output buffering: ob_start and friends. Levels live on {@link PhpContext}; echo writes to the
 * innermost one. Buffers still open when the request ends are flushed by {@link PhpContext#flush}.
 */
final class OutputBufferFunctions {

    private OutputBufferFunctions() {
    }

    static void register() {
        Builtins.def("ob_start", 0, 3, (ctx, a) -> {
            OutputBuffer buf = new OutputBuffer();
            if (a.length > 0 && a[0] != PhpNull.INSTANCE) {
                dev.phpruntime.runtime.PhpCallable.resolve(ctx, a[0], null);
                buf.handler = PhpOps.copyOnAssign(a[0]);
                buf.handlerName = handlerName(a[0]);
            }
            ctx.outputBuffers().add(buf);
            return true;
        });
        Builtins.def("ob_get_contents", 0, 0, (ctx, a) -> {
            OutputBuffer top = top(ctx);
            return top == null ? false : top.text.toString();
        });
        Builtins.def("ob_get_length", 0, 0, (ctx, a) -> {
            OutputBuffer top = top(ctx);
            return top == null ? false : (Object) (long) OutputFunctions.byteLength(top.text.toString());
        });
        Builtins.def("ob_get_level", 0, 0, (ctx, a) -> (long) ctx.outputBuffers().size());
        Builtins.def("ob_get_clean", 0, 0, (ctx, a) -> {
            OutputBuffer top = pop(ctx);
            if (top == null) {
                return false;
            }
            String raw = top.text.toString();
            ctx.applyObHandler(top, raw, 10L);
            return raw;
        });
        Builtins.def("ob_get_flush", 0, 0, (ctx, a) -> {
            OutputBuffer top = pop(ctx);
            if (top == null) {
                return false;
            }
            String raw = top.text.toString();
            ctx.echo(ctx.applyObHandler(top, raw, 9L));
            return raw;
        });
        Builtins.def("ob_end_clean", 0, 0, (ctx, a) -> {
            OutputBuffer top = pop(ctx);
            if (top == null) {
                return false;
            }
            ctx.applyObHandler(top, top.text.toString(), 10L);
            return true;
        });
        Builtins.def("ob_end_flush", 0, 0, (ctx, a) -> {
            OutputBuffer top = pop(ctx);
            if (top == null) {
                return false;
            }
            ctx.echo(ctx.applyObHandler(top, top.text.toString(), 9L));
            return true;
        });
        Builtins.def("ob_clean", 0, 0, (ctx, a) -> {
            OutputBuffer top = top(ctx);
            if (top == null) {
                return false;
            }
            ctx.applyObHandler(top, top.text.toString(), 2L);
            top.text.setLength(0);
            return true;
        });
        Builtins.def("ob_flush", 0, 0, (ctx, a) -> {
            List<OutputBuffer> stack = ctx.outputBuffers();
            if (stack.isEmpty()) {
                return false;
            }
            OutputBuffer top = stack.get(stack.size() - 1);
            String text = top.text.toString();
            top.text.setLength(0);
            // Write into the level below (or the response) without touching this level.
            stack.remove(stack.size() - 1);
            String transformed = ctx.applyObHandler(top, text, 4L);
            ctx.echo(transformed);
            stack.add(top);
            return true;
        });
        Builtins.def("ob_implicit_flush", 0, 1, (ctx, a) -> PhpNull.INSTANCE);
        Builtins.def("ob_list_handlers", 0, 0, (ctx, a) -> {
            PhpArray out = new PhpArray();
            for (OutputBuffer b : ctx.outputBuffers()) {
                out.append(b.handlerName);
            }
            return out;
        });
        Builtins.def("ob_get_status", 0, 1, (ctx, a) -> {
            List<OutputBuffer> stack = ctx.outputBuffers();
            boolean full = a.length > 0 && PhpOps.toBool(a[0]);
            if (full) {
                PhpArray all = new PhpArray();
                for (int i = 0; i < stack.size(); i++) {
                    all.append(status(stack.get(i), i));
                }
                return all;
            }
            return stack.isEmpty() ? new PhpArray() : status(stack.get(stack.size() - 1), stack.size() - 1);
        });
    }

    private static PhpArray status(OutputBuffer b, int level) {
        PhpArray s = new PhpArray();
        s.put("name", b.handlerName);
        s.put("type", b.handler == null ? 0L : 1L);
        s.put("flags", b.handler == null ? 112L : 113L);
        s.put("level", (long) level);
        s.put("chunk_size", 0L);
        s.put("buffer_size", 16384L);
        s.put("buffer_used", (long) OutputFunctions.byteLength(b.text.toString()));
        return s;
    }

    private static OutputBuffer top(PhpContext ctx) {
        List<OutputBuffer> stack = ctx.outputBuffers();
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    private static OutputBuffer pop(PhpContext ctx) {
        List<OutputBuffer> stack = ctx.outputBuffers();
        return stack.isEmpty() ? null : stack.remove(stack.size() - 1);
    }

    /** The name ob_list_handlers shows for a handler callable. */
    private static String handlerName(Object handler) {
        if (handler instanceof String s) {
            return s;
        }
        if (handler instanceof PhpArray arr && arr.size() == 2) {
            Object target = arr.get(0L);
            String cls = target instanceof PhpObject o ? o.phpClass().name() : PhpOps.toStr(target);
            return cls + "::" + PhpOps.toStr(arr.get(1L));
        }
        if (handler instanceof PhpObject o) {
            return o.phpClass().name() + "::__invoke";
        }
        return "default output handler";
    }
}
