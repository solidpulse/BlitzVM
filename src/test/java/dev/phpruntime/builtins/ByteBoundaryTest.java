package dev.phpruntime.builtins;

import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;

/** Test the checker itself, not just successful builtin results. */
class ByteBoundaryTest {
    @Test
    void checkerRejectsNonAsciiArrayKeysAndReferenceWrites() {
        var array = new dev.phpruntime.runtime.PhpArray();
        array.put("😀", "ok");
        var def = new Builtins.Def("broken_array", 0, 0, 0, (ctx, args) -> array);
        assertTrue(assertThrows(IllegalStateException.class, () -> def.call(null, new Object[0])).getMessage().contains("broken_array()"));
        var ref = new Builtins.Def("broken_ref", 1, 1, 1, (ctx, args) -> {
            args[0] = "😀";
            return true;
        });
        assertTrue(assertThrows(IllegalStateException.class, () -> ref.call(null, new Object[]{"ok"})).getMessage().contains("reference argument"));
    }

    @Test
    void invalidUtf8RegexPatternsAreRejected() throws Exception {
        assertThrows(PcreTranslator.PatternError.class, () -> PcreTranslator.compile("/\u00ff/u"));
        var bytes = PcreTranslator.compile("/\\xFF/i");
        assertFalse(bytes.utf);
        assertTrue(bytes.pattern.matcher("\u00ff").find());
        assertEquals(0, bytes.pattern.flags() & (java.util.regex.Pattern.UNICODE_CASE | java.util.regex.Pattern.UNICODE_CHARACTER_CLASS));
    }
}
