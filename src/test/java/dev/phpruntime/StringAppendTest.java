package dev.phpruntime;

import dev.phpruntime.builtins.Builtins;
import dev.phpruntime.nodes.var.LocalVarNode;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpStringBuffer;
import dev.phpruntime.runtime.PhpStrings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StringAppendTest {
    @Test
    void snapshotsAreImmutableAndCachedUntilTheNextAppend() {
        PhpStringBuffer buffer = new PhpStringBuffer("\0\u00ff");
        buffer.append("ab");
        String copy = buffer.asString();
        assertSame(copy, buffer.asString());
        buffer.append("cd");
        assertEquals("\0\u00ffab", copy);
        assertEquals("\0\u00ffabcd", buffer.asString());
        buffer.append("");
        assertSame(buffer.asString(), buffer.asString());
    }

    @Test
    void builtinArgumentsAndResultsRejectEscapedBuffers() {
        assertTrue(PhpStrings.CHECK, "Tests must enable the storage-escape guard");
        PhpStringBuffer buffer = new PhpStringBuffer("bad");
        var argument = new Builtins.Def("append_guard_argument", 1, 1, 0, (ctx, args) -> {
            fail("An escaped buffer must be rejected before running a builtin");
            return PhpNull.INSTANCE;
        });
        assertThrows(IllegalStateException.class, () -> argument.call(null, new Object[]{buffer}));
        var result = new Builtins.Def("append_guard_result", 0, 0, 0, (ctx, args) -> buffer);
        assertThrows(IllegalStateException.class, () -> result.call(null, new Object[0]));
    }

    @Test
    void onlyAppendTargetsCheckPlainSlotsForBuffers() {
        Builtins.def("__append_shape_check", 0, 0, (ctx, args) -> {
            var root = ctx.lookupFunction("append_shape").callTarget().getRootNode();
            root.adoptChildren();
            int[] counts = new int[2];
            root.accept(node -> {
                if (node instanceof LocalVarNode local) {
                    assertEquals(local.varName().equals("s"), local.kind().isAppendTarget(), local.varName());
                    counts[local.kind().isAppendTarget() ? 1 : 0]++;
                }
                return true;
            });
            assertTrue(counts[0] > 0 && counts[1] > 0);
            return PhpNull.INSTANCE;
        });
        try (var ctx = org.graalvm.polyglot.Context.newBuilder("php").build()) {
            ctx.eval("php", "<?php function append_shape($n) { $s = ''; for ($i = 0; $i < $n; $i++) { $s .= 'ab'; } return $s; } __append_shape_check();");
        }
    }
}
