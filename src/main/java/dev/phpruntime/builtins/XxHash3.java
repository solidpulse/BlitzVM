package dev.phpruntime.builtins;

/**
 * XXH3 128-bit, used by hash('xxh128', ...). Pure Java port of the reference
 * XXH3_len_* and XXH3_hashLong_128b functions from xxHash 0.8.
 */
final class XxHash3 {
    private XxHash3() {
    }

    static final long PRIME32_1 = 0x9E3779B1L;
    static final long PRIME32_2 = 0x85EBCA77L;
    static final long PRIME32_3 = 0xC2B2AE3DL;
    static final long PRIME64_1 = 0x9E3779B185EBCA87L;
    static final long PRIME64_2 = 0xC2B2AE3D27D4EB4FL;
    static final long PRIME64_3 = 0x165667B19E3779F9L;
    static final long PRIME64_4 = 0x85EBCA77C2B2AE63L;
    static final long PRIME64_5 = 0x27D4EB2F165667C5L;

    private static final byte[] SECRET = {
            (byte) 0xb8, (byte) 0xfe, (byte) 0x6c, (byte) 0x39, (byte) 0x23, (byte) 0xa4, (byte) 0x4b, (byte) 0xbe,
            (byte) 0x7c, (byte) 0x01, (byte) 0x81, (byte) 0x2c, (byte) 0xf7, (byte) 0x21, (byte) 0xad, (byte) 0x1c,
            (byte) 0xde, (byte) 0xd4, (byte) 0x6d, (byte) 0xe9, (byte) 0x83, (byte) 0x90, (byte) 0x97, (byte) 0xdb,
            (byte) 0x72, (byte) 0x40, (byte) 0xa4, (byte) 0xa4, (byte) 0xb7, (byte) 0xb3, (byte) 0x67, (byte) 0x1f,
            (byte) 0xcb, (byte) 0x79, (byte) 0xe6, (byte) 0x4e, (byte) 0xcc, (byte) 0xc0, (byte) 0xe5, (byte) 0x78,
            (byte) 0x82, (byte) 0x5a, (byte) 0xd0, (byte) 0x7d, (byte) 0xcc, (byte) 0xff, (byte) 0x72, (byte) 0x21,
            (byte) 0xb8, (byte) 0x08, (byte) 0x46, (byte) 0x74, (byte) 0xf7, (byte) 0x43, (byte) 0x24, (byte) 0x8e,
            (byte) 0xe0, (byte) 0x35, (byte) 0x90, (byte) 0xe6, (byte) 0x81, (byte) 0x3a, (byte) 0x26, (byte) 0x4c,
            (byte) 0x3c, (byte) 0x28, (byte) 0x52, (byte) 0xbb, (byte) 0x91, (byte) 0xc3, (byte) 0x00, (byte) 0xcb,
            (byte) 0x88, (byte) 0xd0, (byte) 0x65, (byte) 0x8b, (byte) 0x1b, (byte) 0x53, (byte) 0x2e, (byte) 0xa3,
            (byte) 0x71, (byte) 0x64, (byte) 0x48, (byte) 0x97, (byte) 0xa2, (byte) 0x0d, (byte) 0xf9, (byte) 0x4e,
            (byte) 0x38, (byte) 0x19, (byte) 0xef, (byte) 0x46, (byte) 0xa9, (byte) 0xde, (byte) 0xac, (byte) 0xd8,
            (byte) 0xa8, (byte) 0xfa, (byte) 0x76, (byte) 0x3f, (byte) 0xe3, (byte) 0x9c, (byte) 0x34, (byte) 0x3f,
            (byte) 0xf9, (byte) 0xdc, (byte) 0xbb, (byte) 0xc7, (byte) 0xc7, (byte) 0x0b, (byte) 0x4f, (byte) 0x1d,
            (byte) 0x8a, (byte) 0x51, (byte) 0xe0, (byte) 0x4b, (byte) 0xcd, (byte) 0xb4, (byte) 0x59, (byte) 0x31,
            (byte) 0xc8, (byte) 0x9f, (byte) 0x7e, (byte) 0xc9, (byte) 0xd9, (byte) 0x78, (byte) 0x73, (byte) 0x64,
            (byte) 0xea, (byte) 0xc5, (byte) 0xac, (byte) 0x83, (byte) 0x34, (byte) 0xd3, (byte) 0xeb, (byte) 0xc3,
            (byte) 0xc5, (byte) 0x81, (byte) 0xa0, (byte) 0xff, (byte) 0xfa, (byte) 0x13, (byte) 0x63, (byte) 0xeb,
            (byte) 0x17, (byte) 0x0d, (byte) 0xdd, (byte) 0x51, (byte) 0xb7, (byte) 0xf0, (byte) 0xda, (byte) 0x49,
            (byte) 0xd3, (byte) 0x16, (byte) 0x55, (byte) 0x26, (byte) 0x29, (byte) 0xd4, (byte) 0x68, (byte) 0x9e,
            (byte) 0x2b, (byte) 0x16, (byte) 0xbe, (byte) 0x58, (byte) 0x7d, (byte) 0x47, (byte) 0xa1, (byte) 0xfc,
            (byte) 0x8f, (byte) 0xf8, (byte) 0xb8, (byte) 0xd1, (byte) 0x7a, (byte) 0xd0, (byte) 0x31, (byte) 0xce,
            (byte) 0x45, (byte) 0xcb, (byte) 0x3a, (byte) 0x8f, (byte) 0x95, (byte) 0x16, (byte) 0x04, (byte) 0x28,
            (byte) 0xaf, (byte) 0xd7, (byte) 0xfb, (byte) 0xca, (byte) 0xbb, (byte) 0x4b, (byte) 0x40, (byte) 0x7e
    };

    /** Returns {low64, high64}. */
    static long[] hash128(byte[] input) {
        return hash128(input, 0, input.length, 0L);
    }

    static long[] hash128(byte[] input, int off, int len, long seed) {
        if (len <= 16) {
            return len0to16(input, off, len, seed);
        }
        if (len <= 128) {
            return len17to128(input, off, len, seed);
        }
        if (len <= 240) {
            return len129to240(input, off, len, seed);
        }
        return hashLong(input, off, len, seed);
    }

    private static long[] len0to16(byte[] in, int off, int len, long seed) {
        if (len > 8) {
            return len9to16(in, off, len, seed);
        }
        if (len >= 4) {
            return len4to8(in, off, len, seed);
        }
        if (len > 0) {
            return len1to3(in, off, len, seed);
        }
        long low = xxh64Avalanche(seed ^ (read64(SECRET, 64) ^ read64(SECRET, 72)));
        long high = xxh64Avalanche(seed ^ (read64(SECRET, 80) ^ read64(SECRET, 88)));
        return new long[]{low, high};
    }

    private static long[] len1to3(byte[] in, int off, int len, long seed) {
        int c1 = in[off] & 0xff;
        int c2 = in[off + (len >> 1)] & 0xff;
        int c3 = in[off + len - 1] & 0xff;
        long combinedl = ((long) c1 << 16) | ((long) c2 << 24) | ((long) c3) | ((long) len << 8);
        long combinedh = Integer.toUnsignedLong(Integer.rotateLeft(Integer.reverseBytes((int) combinedl), 13));
        long bitflipl = (read32(SECRET, 0) ^ read32(SECRET, 4)) + seed;
        long bitfliph = (read32(SECRET, 8) ^ read32(SECRET, 12)) - seed;
        long keyedLo = combinedl ^ bitflipl;
        long keyedHi = combinedh ^ bitfliph;
        return new long[]{xxh64Avalanche(keyedLo), xxh64Avalanche(keyedHi)};
    }

    private static long[] len4to8(byte[] in, int off, int len, long seed) {
        seed ^= (long) Integer.toUnsignedLong(Integer.reverseBytes((int) seed)) << 32;
        long inputLo = read32(in, off);
        long inputHi = read32(in, off + len - 4);
        long input64 = inputLo + (inputHi << 32);
        long bitflip = (read64(SECRET, 16) ^ read64(SECRET, 24)) + seed;
        long keyed = input64 ^ bitflip;
        long[] m128 = mul128(keyed, PRIME64_1 + ((long) len << 2));
        m128[1] += m128[0] << 1;
        m128[0] ^= m128[1] >>> 3;
        m128[0] = xorshift64(m128[0], 35);
        m128[0] *= 0x9FB21C651E98DF25L;
        m128[0] = xorshift64(m128[0], 28);
        m128[1] = avalanche(m128[1]);
        return m128;
    }

    private static long[] len9to16(byte[] in, int off, int len, long seed) {
        long bitflipl = read64(SECRET, 32) ^ read64(SECRET, 40);
        long bitfliph = read64(SECRET, 48) ^ read64(SECRET, 56);
        long inputLo = read64(in, off);
        long inputHi = read64(in, off + len - 8);
        long[] m128 = mul128(inputLo ^ inputHi ^ bitflipl, PRIME64_1);
        m128[0] += (long) (len - 1) << 54;
        inputHi ^= bitfliph;
        m128[1] += inputHi + ((long) (int) inputHi & 0xFFFFFFFFL) * (PRIME32_2 - 1);
        m128[0] ^= Long.reverseBytes(m128[1]);
        long[] h128 = mul128(m128[0], PRIME64_2);
        h128[1] += m128[1] * PRIME64_2;
        return new long[]{avalanche(h128[0]), avalanche(h128[1])};
    }

    private static long[] len17to128(byte[] in, int off, int len, long seed) {
        long[] acc = new long[]{len * PRIME64_1, 0};
        if (len > 32) {
            if (len > 64) {
                if (len > 96) {
                    acc = mix32B(acc, in, off + 48, off + len - 64, SECRET, 96, seed);
                }
                acc = mix32B(acc, in, off + 32, off + len - 48, SECRET, 64, seed);
            }
            acc = mix32B(acc, in, off + 16, off + len - 32, SECRET, 32, seed);
        }
        acc = mix32B(acc, in, off, off + len - 16, SECRET, 0, seed);
        return finishShort(acc, len);
    }

    /** The 128-bit short-input finalizer: both halves are mixed, the high one negated. */
    private static long[] finishShort(long[] acc, int len) {
        long low = avalanche(acc[0] + acc[1]);
        long high = -avalanche(acc[0] * PRIME64_1 + acc[1] * PRIME64_4 + len * PRIME64_2);
        return new long[]{low, high};
    }

    private static long[] len129to240(byte[] in, int off, int len, long seed) {
        long[] acc = new long[]{len * PRIME64_1, 0};
        int nbRounds = len / 32;
        for (int i = 0; i < 4; i++) {
            acc = mix32B(acc, in, off + 32 * i, off + 32 * i + 16, SECRET, 32 * i, seed);
        }
        acc[0] = avalanche(acc[0]);
        acc[1] = avalanche(acc[1]);
        for (int i = 4; i < nbRounds; i++) {
            acc = mix32B(acc, in, off + 32 * i, off + 32 * i + 16, SECRET, 3 + 32 * (i - 4), seed);
        }
        acc = mix32B(acc, in, off + len - 16, off + len - 32, SECRET, 136 - 17 - 16, seed);
        return finishShort(acc, len);
    }

    private static long[] hashLong(byte[] in, int off, int len, long seed) {
        long[] acc = {PRIME32_3, PRIME64_1, PRIME64_2, PRIME64_3, PRIME64_4, PRIME32_2, PRIME64_5, PRIME32_1};
        int secretSize = SECRET.length;
        int stripesPerBlock = (secretSize - 64) / 8;
        int blockLen = 64 * stripesPerBlock;
        int blocks = (len - 1) / blockLen;
        for (int n = 0; n < blocks; n++) {
            accumulate(acc, in, off + n * blockLen, SECRET, 0, stripesPerBlock);
            scramble(acc, SECRET, secretSize - 64);
        }
        int stripes = ((len - 1) - blockLen * blocks) / 64;
        accumulate(acc, in, off + blocks * blockLen, SECRET, 0, stripes);
        accumulate512(acc, in, off + len - 64, SECRET, secretSize - 64 - 7);
        long low = mergeAccs(acc, 11, len * PRIME64_1);
        long high = mergeAccs(acc, secretSize - 64 - 11, ~(len * PRIME64_2));
        return new long[]{low, high};
    }

    private static void accumulate(long[] acc, byte[] in, int off, byte[] secret, int secretOff, int stripes) {
        for (int n = 0; n < stripes; n++) {
            accumulate512(acc, in, off + n * 64, secret, secretOff + n * 8);
        }
    }

    private static void accumulate512(long[] acc, byte[] in, int off, byte[] secret, int secretOff) {
        for (int i = 0; i < 8; i++) {
            long dataVal = read64(in, off + 8 * i);
            long dataKey = dataVal ^ read64(secret, secretOff + 8 * i);
            acc[i ^ 1] += dataVal;
            acc[i] += (dataKey & 0xFFFFFFFFL) * (dataKey >>> 32);
        }
    }

    private static void scramble(long[] acc, byte[] secret, int secretOff) {
        for (int i = 0; i < 8; i++) {
            acc[i] ^= acc[i] >>> 47;
            acc[i] ^= read64(secret, secretOff + 8 * i);
            acc[i] *= PRIME32_1;
        }
    }

    private static long mergeAccs(long[] acc, int secretOff, long start) {
        long result = start;
        for (int i = 0; i < 4; i++) {
            result += mix2Accs(acc[2 * i], acc[2 * i + 1], SECRET, secretOff + 16 * i);
        }
        return avalanche(result);
    }

    private static long mix2Accs(long acc0, long acc1, byte[] secret, int off) {
        return mul128Fold64(acc0 ^ read64(secret, off), acc1 ^ read64(secret, off + 8));
    }

    private static long[] mix32B(long[] acc, byte[] in, int off1, int off2, byte[] secret, int secretOff,
                                 long seed) {
        long low = acc[0] + mix16B(in, off1, secret, secretOff, seed);
        low ^= read64(in, off2) + read64(in, off2 + 8);
        long high = acc[1] + mix16B(in, off2, secret, secretOff + 16, seed);
        high ^= read64(in, off1) + read64(in, off1 + 8);
        return new long[]{low, high};
    }

    private static long mix16B(byte[] in, int off, byte[] secret, int secretOff, long seed) {
        long lo = read64(in, off);
        long hi = read64(in, off + 8);
        return mul128Fold64(lo ^ (read64(secret, secretOff) + seed), hi ^ (read64(secret, secretOff + 8) - seed));
    }

    static long avalanche(long h) {
        h ^= h >>> 37;
        h *= 0x165667919E3779F9L;
        h ^= h >>> 32;
        return h;
    }

    static long xxh64Avalanche(long h) {
        h ^= h >>> 33;
        h *= PRIME64_2;
        h ^= h >>> 29;
        h *= PRIME64_3;
        h ^= h >>> 32;
        return h;
    }

    private static long xorshift64(long value, int shift) {
        return value ^ (value >>> shift);
    }

    static long mul128Fold64(long a, long b) {
        long[] p = mul128(a, b);
        return p[0] ^ p[1];
    }

    /** Unsigned 128-bit multiply: {low64, high64}. */
    static long[] mul128(long a, long b) {
        long high = Math.multiplyHigh(a, b);
        long low = a * b;
        if (a < 0) {
            high += b;
        }
        if (b < 0) {
            high += a;
        }
        return new long[]{low, high};
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
