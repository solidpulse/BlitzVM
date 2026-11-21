package dev.phpruntime.builtins;

/** XXH64, used by hash('xxh64', ...). Kept separate from the XXH3 128-bit port. */
final class XxHash64 {
    private XxHash64() {
    }

    private static final long P1 = 0x9E3779B185EBCA87L;
    private static final long P2 = 0xC2B2AE3D27D4EB4FL;
    private static final long P3 = 0x165667B19E3779F9L;
    private static final long P4 = 0x85EBCA77C2B2AE63L;
    private static final long P5 = 0x27D4EB2F165667C5L;

    static long hash(byte[] input) {
        return hash(input, 0, input.length, 0L);
    }

    static long hash(byte[] data, int off, int len, long seed) {
        long h;
        int i = off;
        int end = off + len;
        if (len >= 32) {
            long v1 = seed + P1 + P2;
            long v2 = seed + P2;
            long v3 = seed;
            long v4 = seed - P1;
            int limit = end - 32;
            do {
                v1 = round(v1, read64(data, i));
                v2 = round(v2, read64(data, i + 8));
                v3 = round(v3, read64(data, i + 16));
                v4 = round(v4, read64(data, i + 24));
                i += 32;
            } while (i <= limit);
            h = Long.rotateLeft(v1, 1) + Long.rotateLeft(v2, 7)
                    + Long.rotateLeft(v3, 12) + Long.rotateLeft(v4, 18);
            h = mergeRound(h, v1);
            h = mergeRound(h, v2);
            h = mergeRound(h, v3);
            h = mergeRound(h, v4);
        } else {
            h = seed + P5;
        }
        h += len;
        while (i + 8 <= end) {
            h ^= round(0, read64(data, i));
            h = Long.rotateLeft(h, 27) * P1 + P4;
            i += 8;
        }
        if (i + 4 <= end) {
            h ^= read32(data, i) * P1;
            h = Long.rotateLeft(h, 23) * P2 + P3;
            i += 4;
        }
        while (i < end) {
            h ^= (data[i] & 0xFFL) * P5;
            h = Long.rotateLeft(h, 11) * P1;
            i++;
        }
        return avalanche(h);
    }

    private static long round(long acc, long input) {
        acc += input * P2;
        acc = Long.rotateLeft(acc, 31);
        acc *= P1;
        return acc;
    }

    private static long mergeRound(long acc, long value) {
        acc ^= round(0, value);
        acc = acc * P1 + P4;
        return acc;
    }

    private static long avalanche(long h) {
        h ^= h >>> 33;
        h *= P2;
        h ^= h >>> 29;
        h *= P3;
        h ^= h >>> 32;
        return h;
    }

    private static long read32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8) | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24);
    }

    private static long read64(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8) | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24) | ((b[off + 4] & 0xFFL) << 32) | ((b[off + 5] & 0xFFL) << 40)
                | ((b[off + 6] & 0xFFL) << 48) | ((b[off + 7] & 0xFFL) << 56);
    }
}
