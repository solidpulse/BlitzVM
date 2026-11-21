package dev.phpruntime.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PhpArrayTest {

    @Test
    void storageMatchesOrderedMapThroughCollisionsAndCompaction() {
        PhpArray array = new PhpArray();
        Map<Object, Object> expected = new LinkedHashMap<>();
        List<Object> keys = new ArrayList<>();
        for (long i = 0; i < 300; i++) {
            keys.add(i);
            // These strings have identical String hashes, including every concatenation.
            StringBuilder s = new StringBuilder();
            for (int bit = 0; bit < 9; bit++) { s.append((i & (1 << bit)) == 0 ? "Aa" : "BB"); }
            keys.add(s.toString());
        }
        Random random = new Random(14);
        for (int i = 0; i < 20000; i++) {
            Object key = keys.get(random.nextInt(keys.size()));
            if (random.nextInt(3) == 0) {
                expected.remove(key);
                array.remove(key);
            } else {
                expected.put(key, (long) i);
                array.put(key, (long) i);
            }
            assertEquals(expected.get(key), array.get(key));
            assertEquals(expected.containsKey(key), array.containsKey(key));
            if (i % 100 == 0) {
                assertEquals(new ArrayList<>(expected.entrySet()), array.entries());
                assertEquals(new ArrayList<>(expected.keySet()), array.keys());
                assertEquals(new ArrayList<>(expected.values()), array.values());
                assertEquals(expected.size(), array.size());
            }
        }
    }

    @Test
    void packedCopyAndReferencesKeepValueSemantics() {
        PhpArray inner = PhpArray.ofList(List.of(1L));
        PhpReference ref = new PhpReference(2L);
        PhpArray array = PhpArray.ofList(List.of(inner, ref, 3L));
        PhpArray copy = array.share().forWrite();
        PhpArray copyInner = ((PhpArray) copy.get(0L)).forWrite();
        copy.put(0L, copyInner);
        copyInner.append(4L);
        copy.put(1L, 5L);
        copy.put(2L, 6L);
        assertEquals(List.of(1L), inner.values());
        assertEquals(List.of(1L, 4L), copyInner.values());
        assertEquals(5L, array.get(1L));
        assertEquals(3L, array.get(2L));
        assertSame(ref, copy.getRaw(1L));
    }

    @Test
    void cursorsSkipHolesAndTemporarySharesRelease() {
        PhpArray array = PhpArray.ofList(List.of(0L, 1L, 2L, 3L));
        array.remove(1L);
        array.put("tail", 4L);
        List<Object> keys = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (int pos = array.firstPos(); pos >= 0; pos = array.nextPos(pos)) {
            keys.add(array.keyAt(pos));
            values.add(array.rawValueAt(pos));
        }
        assertEquals(List.of(0L, 2L, 3L, "tail"), keys);
        assertEquals(List.of(0L, 2L, 3L, 4L), values);
        array.share();
        array.releaseShare();
        assertSame(array, array.forWrite());
        array.share();
        assertNotSame(array, array.forWrite());
        array.releaseShare();
        assertSame(array, array.forWrite());
    }

    /**
     * The inline read/write/remove paths must produce exactly the state the boundary methods
     * produce, including hash collisions, deleted slots, growing and nextIndex after a removal.
     */
    @Test
    void inlinePathsMatchBoundaryPaths() {
        List<Object> keys = new ArrayList<>();
        for (long i = -4; i <= 40; i++) {
            keys.add(i);
        }
        for (int i = 0; i < 60; i++) {
            String s = new StringBuilder().append((char) ('a' + i % 26)).append(i).toString();
            keys.add(s);
            // All of these have the same String hash, so they collide in the probe.
            StringBuilder collision = new StringBuilder();
            for (int bit = 0; bit < 9; bit++) {
                collision.append((i & (1 << bit)) == 0 ? "Aa" : "BB");
            }
            keys.add(collision.toString());
        }
        keys.add("5");
        keys.add("05");
        keys.add("-0");
        keys.add("-5");
        keys.add("9223372036854775808");
        keys.add("");
        keys.add(true);
        keys.add(false);
        keys.add(1.7);
        keys.add(-2.9);
        keys.add(PhpNull.INSTANCE);

        Random random = new Random(38);
        for (int layout = 0; layout < 2; layout++) {
            PhpArray boundary = new PhpArray();
            PhpArray inline = new PhpArray();
            if (layout == 1) {
                // Start in the hash layout, so the probe and insert paths run from the start.
                boundary.put("seed", 0L);
                inline.putFast("seed", 0L);
            }
            for (int i = 0; i < 12000; i++) {
                Object key = keys.get(random.nextInt(keys.size()));
                int op = random.nextInt(5);
                if (op == 0) {
                    boundary.remove(key);
                    inline.removeFast(key);
                } else if (op == 1) {
                    boundary.append((long) i);
                    inline.appendWritable((long) i);
                } else if (op == 2) {
                    // Round-trip the key through a KnownKey, as a constant key site does.
                    PhpArray.KnownKey known = PhpArray.knownKey(key);
                    boundary.put(known.key(), (long) i);
                    inline.putKnown(known, (long) i);
                } else {
                    boundary.put(key, (long) i);
                    inline.putFast(key, (long) i);
                }
                assertEquals(boundary.size(), inline.size());
                assertEquals(boundary.keys(), inline.keys(), "keys after op " + op + " on " + key);
                assertEquals(boundary.values(), inline.values(), "values after op " + op + " on " + key);
                Object expected = boundary.get(key);
                assertEquals(expected, inline.getFast(key));
                assertEquals(boundary.containsKey(key), inline.containsKeyFast(key));
                if (key instanceof String || key instanceof Long) {
                    assertEquals(expected, inline.getKnown(PhpArray.knownKey(key)));
                }
                if (i % 500 == 0) {
                    assertEquals(boundary.entries(), inline.entries());
                    List<Object> cursorKeys = new ArrayList<>();
                    List<Object> cursorValues = new ArrayList<>();
                    for (int pos = inline.firstPos(); pos >= 0; pos = inline.nextPos(pos)) {
                        cursorKeys.add(inline.keyAt(pos));
                        cursorValues.add(inline.rawValueAt(pos));
                    }
                    assertEquals(boundary.keys(), cursorKeys);
                    assertEquals(boundary.rawValues(), cursorValues);
                }
            }
        }
    }
}
