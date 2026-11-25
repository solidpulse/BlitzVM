package dev.phpruntime.builtins;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shape analysis must promote only bodies whose behaviour the scanners fully model. */
class PregFastPathTest {

    @Test
    void simpleBodiesGetFastMatchers() throws Exception {
        assertNotNull(PcreTranslator.compile("/abc/").fast);
        assertNotNull(PcreTranslator.compile("/abc/i").fast);
        assertNotNull(PcreTranslator.compile("/[&|]/").fast);
        assertNotNull(PcreTranslator.compile("/[&|]+/").fast);
        assertNotNull(PcreTranslator.compile("/[ \\t]+#/").fast);
        assertNotNull(PcreTranslator.compile("/^(?:\\/)+/u").fast);
        assertNotNull(PcreTranslator.compile("/^[a-z]+/").fast);
        assertNotNull(PcreTranslator.compile("/^(?:\\+|-)?0o(?P<value>[0-7_]++)$/").fast);
        assertTrue(PcreTranslator.compile("//u").empty);
    }

    @Test
    void unmodelledShapesFallBack() throws Exception {
        assertNull(PcreTranslator.compile("/[k]/iu").fast); // PCRE folds Kelvin sign to k
        assertNull(PcreTranslator.compile("/[^a]/u").fast); // code points beyond ASCII
        assertNull(PcreTranslator.compile("/[a]{2,3}/").fast);
        assertNull(PcreTranslator.compile("/(?:ab)+/").fast);
        assertNull(PcreTranslator.compile("/[a]+?/").fast);
        assertNull(PcreTranslator.compile("/^abc/m").fast);
        assertNull(PcreTranslator.compile("/a.b/s").fast);
        assertNull(PcreTranslator.compile("/(a)(b)?(c)/").fast);
        assertNull(PcreTranslator.compile("/abc/D").fast);
        // No matcher is chosen by a pattern's exact text, only by its shape.
        assertNull(PcreTranslator.compile("/\\s*#.*$/A").fast);
        assertNull(PcreTranslator.compile("/^[\\s\\r\\n]?---[\\s\\r\\n]?$/sm").fast);
        assertFalse(PcreTranslator.compile("/abc/").empty);
    }
}
