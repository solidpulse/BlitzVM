package dev.phpruntime.builtins;

import com.oracle.truffle.api.object.DynamicObject;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.call.CallArgHelper;
import dev.phpruntime.runtime.EnumCaseState;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpClassTemplate;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNativeState;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpStrings;
import dev.phpruntime.runtime.Visibility;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PHP's random extension: the engine classes, {@code Random\Randomizer} and the seeded
 * algorithms they share with the procedural {@code mt_rand()} family.
 */
public final class RandomClasses {

    private RandomClasses() {
    }

    static final String ENGINE = "Random\\Engine";
    static final String CRYPTO_ENGINE = "Random\\CryptoSafeEngine";
    static final String MT19937 = "Random\\Engine\\Mt19937";
    static final String PCG = "Random\\Engine\\PcgOneseq128XslRr64";
    static final String XOSHIRO = "Random\\Engine\\Xoshiro256StarStar";
    static final String SECURE = "Random\\Engine\\Secure";
    static final String RANDOMIZER = "Random\\Randomizer";
    static final String INTERVAL = "Random\\IntervalBoundary";

    static final String BROKEN_ENGINE = "Random\\BrokenRandomEngineError";
    static final String RANDOM_ERROR = "Random\\RandomError";
    static final String RANDOM_EXCEPTION = "Random\\RandomException";

    static final String BROKEN_MESSAGE = "Failed to generate an acceptable random number in 50 attempts";
    static final String EMPTY_ENGINE_MESSAGE = "A random engine must return a non-empty string";

    // ------------------------------------------------------------------ chunk source

    /** One engine output: a numeric value and the number of bytes it came from. */
    interface ChunkSource {
        long nextValue();

        int chunkSize();

        byte[] nextBytes();
    }

    /** Native state of one of the built-in engines. */
    abstract static class EngineState implements PhpNativeState, ChunkSource {
        abstract PhpArray stateArray();

        abstract void restore(PhpArray data);

        @Override
        public Map<String, Object> debugProperties(PhpObject self) {
            return Map.of("__states", stateArray());
        }
    }

    // ------------------------------------------------------------------ MT19937

    static final class Mt19937State extends EngineState {
        private static final int N = 624;
        private static final int M = 397;
        private static final int MATRIX_A = 0x9908b0df;
        private static final int UPPER = 0x80000000;
        private static final int LOWER = 0x7fffffff;

        final int[] state = new int[N];
        int count;
        int mode;

        Mt19937State(int seed, int mode) {
            this.mode = mode;
            reseed(seed);
        }

        void reseed(int seed) {
            state[0] = seed;
            for (int i = 1; i < N; i++) {
                state[i] = 1812433253 * (state[i - 1] ^ (state[i - 1] >>> 30)) + i;
            }
            reload();
        }

        private void reload() {
            if (mode == 1) {
                for (int i = 0; i < N; i++) {
                    int current = state[i];
                    int t = (current & UPPER) | (state[(i + 1) % N] & LOWER);
                    state[i] = state[(i + M) % N] ^ (t >>> 1) ^ ((current & 1) != 0 ? MATRIX_A : 0);
                }
            } else {
                for (int i = 0; i < N; i++) {
                    int t = (state[i] & UPPER) | (state[(i + 1) % N] & LOWER);
                    state[i] = state[(i + M) % N] ^ (t >>> 1) ^ ((t & 1) != 0 ? MATRIX_A : 0);
                }
            }
            count = 0;
        }

        private int nextWord() {
            if (count >= N) {
                reload();
            }
            int v = state[count++];
            v ^= v >>> 11;
            v ^= (v << 7) & 0x9d2c5680;
            v ^= (v << 15) & 0xefc60000;
            v ^= v >>> 18;
            return v;
        }

        @Override
        public long nextValue() {
            return nextWord() & 0xFFFFFFFFL;
        }

        @Override
        public int chunkSize() {
            return 4;
        }

        @Override
        public byte[] nextBytes() {
            long v = nextWord() & 0xFFFFFFFFL;
            return new byte[]{(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)};
        }

        @Override
        PhpArray stateArray() {
            PhpArray out = new PhpArray();
            for (int word : state) {
                out.append(String.format("%08x", Integer.reverseBytes(word)));
            }
            out.append((long) count);
            out.append((long) mode);
            return out;
        }

        @Override
        void restore(PhpArray data) {
            if (data.size() != N + 2) {
                throw invalidSerialization(MT19937);
            }
            for (int i = 0; i < N; i++) {
                Object v = data.get((long) i);
                if (!(v instanceof String s) || s.length() != 8) {
                    throw invalidSerialization(MT19937);
                }
                state[i] = Integer.reverseBytes((int) parseHex(s, 8, MT19937));
            }
            Object countValue = data.get((long) N);
            Object modeValue = data.get((long) (N + 1));
            if (!(countValue instanceof Long l) || !(modeValue instanceof Long m) || l < 0 || l > N
                    || (m != 0 && m != 1)) {
                throw invalidSerialization(MT19937);
            }
            count = (int) (long) l;
            mode = (int) (long) m;
        }

        @Override
        public PhpNativeState copy() {
            Mt19937State copy = new Mt19937State(0, mode);
            System.arraycopy(state, 0, copy.state, 0, N);
            copy.count = count;
            return copy;
        }
    }

    // ------------------------------------------------------------------ PCG

    static final class PcgState extends EngineState {
        // The increment PHP uses with the state laid out as (high, low).
        private static final long MUL_HIGH = 0x2360ed051fc65da4L;
        private static final long MUL_LOW = 0x4385df649fccf645L;
        private static final long INC_HIGH = 0x5851f42d4c957f2dL;
        private static final long INC_LOW = 0x14057b7ef767814fL;

        private static final BigInteger MUL = unsigned128(MUL_HIGH, MUL_LOW);
        private static final BigInteger INC = unsigned128(INC_HIGH, INC_LOW);
        private static final BigInteger MASK128 = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);

        long high;
        long low;

        PcgState(long seed) {
            BigInteger init = new BigInteger(Long.toUnsignedString(seed));
            seed128(init);
        }

        PcgState(byte[] seed) {
            long first = littleEndian64(seed, 0);
            long second = littleEndian64(seed, 8);
            seed128(unsigned128(first, second));
        }

        private void seed128(BigInteger initstate) {
            BigInteger x = BigInteger.ZERO;
            x = x.multiply(MUL).add(INC).and(MASK128);
            x = x.add(initstate).and(MASK128);
            x = x.multiply(MUL).add(INC).and(MASK128);
            set(x);
        }

        private void set(BigInteger x) {
            high = x.shiftRight(64).longValue();
            low = x.longValue();
        }

        private BigInteger get() {
            return unsigned128(high, low);
        }

        private void step() {
            long productLow = low * MUL_LOW;
            long newLow = productLow + INC_LOW;
            long carry = Long.compareUnsigned(newLow, productLow) < 0 ? 1 : 0;
            long newHigh = Math.unsignedMultiplyHigh(low, MUL_LOW)
                    + high * MUL_LOW + low * MUL_HIGH + INC_HIGH + carry;
            high = newHigh;
            low = newLow;
        }

        @Override
        public long nextValue() {
            step();
            return Long.rotateRight(high ^ low, (int) (high >>> 58));
        }

        @Override
        public int chunkSize() {
            return 8;
        }

        @Override
        public byte[] nextBytes() {
            return littleEndianBytes(nextValue());
        }

        void jump(long advance) {
            BigInteger accMult = BigInteger.ONE;
            BigInteger accPlus = BigInteger.ZERO;
            BigInteger curMult = MUL;
            BigInteger curPlus = INC;
            long delta = advance;
            while (delta != 0) {
                if ((delta & 1) != 0) {
                    accMult = accMult.multiply(curMult).and(MASK128);
                    accPlus = accPlus.multiply(curMult).add(curPlus).and(MASK128);
                }
                curPlus = curMult.add(BigInteger.ONE).multiply(curPlus).and(MASK128);
                curMult = curMult.multiply(curMult).and(MASK128);
                delta >>>= 1;
            }
            set(get().multiply(accMult).add(accPlus).and(MASK128));
        }

        @Override
        PhpArray stateArray() {
            PhpArray out = new PhpArray();
            out.append(hexLittleEndian(high));
            out.append(hexLittleEndian(low));
            return out;
        }

        @Override
        void restore(PhpArray data) {
            if (data.size() != 2) {
                throw invalidSerialization(PCG);
            }
            high = parseHexLittleEndian(data.get(0L), PCG);
            low = parseHexLittleEndian(data.get(1L), PCG);
        }

        @Override
        public PhpNativeState copy() {
            PcgState copy = new PcgState(0);
            copy.high = high;
            copy.low = low;
            return copy;
        }
    }

    // ------------------------------------------------------------------ Xoshiro256**

    static final class XoshiroState extends EngineState {
        private static final long[] JUMP = {
                0x180ec6d33cfd0abaL, 0xd5a61266f0c9392cL, 0xa9582618e03fc9aaL, 0x39abdc4529b1661cL
        };
        private static final long[] LONG_JUMP = {
                0x76e15d3efefdcbbfL, 0xc5004e441c522fb3L, 0x77710069854ee241L, 0x39109bb02acbe635L
        };

        final long[] state = new long[4];

        XoshiroState(long seed) {
            long x = seed;
            for (int i = 0; i < 4; i++) {
                x += 0x9e3779b97f4a7c15L;
                long z = x;
                z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
                z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
                state[i] = z ^ (z >>> 31);
            }
        }

        XoshiroState(byte[] seed) {
            for (int i = 0; i < 4; i++) {
                state[i] = littleEndian64(seed, i * 8);
            }
        }

        private static long rotl(long x, int k) {
            return Long.rotateLeft(x, k);
        }

        @Override
        public long nextValue() {
            long result = rotl(state[1] * 5, 7) * 9;
            long t = state[1] << 17;
            state[2] ^= state[0];
            state[3] ^= state[1];
            state[1] ^= state[2];
            state[0] ^= state[3];
            state[2] ^= t;
            state[3] = rotl(state[3], 45);
            return result;
        }

        @Override
        public int chunkSize() {
            return 8;
        }

        @Override
        public byte[] nextBytes() {
            return littleEndianBytes(nextValue());
        }

        private void jump(long[] constants) {
            long s0 = 0;
            long s1 = 0;
            long s2 = 0;
            long s3 = 0;
            for (long constant : constants) {
                for (int b = 0; b < 64; b++) {
                    if ((constant & (1L << b)) != 0) {
                        s0 ^= state[0];
                        s1 ^= state[1];
                        s2 ^= state[2];
                        s3 ^= state[3];
                    }
                    long t = state[1] << 17;
                    state[2] ^= state[0];
                    state[3] ^= state[1];
                    state[1] ^= state[2];
                    state[0] ^= state[3];
                    state[2] ^= t;
                    state[3] = rotl(state[3], 45);
                }
            }
            state[0] = s0;
            state[1] = s1;
            state[2] = s2;
            state[3] = s3;
        }

        void jump() {
            jump(JUMP);
        }

        void jumpLong() {
            jump(LONG_JUMP);
        }

        @Override
        PhpArray stateArray() {
            PhpArray out = new PhpArray();
            for (long word : state) {
                out.append(hexLittleEndian(word));
            }
            return out;
        }

        @Override
        void restore(PhpArray data) {
            if (data.size() != 4) {
                throw invalidSerialization(XOSHIRO);
            }
            for (int i = 0; i < 4; i++) {
                state[i] = parseHexLittleEndian(data.get((long) i), XOSHIRO);
            }
            if ((state[0] | state[1] | state[2] | state[3]) == 0) {
                throw invalidSerialization(XOSHIRO);
            }
        }

        @Override
        public PhpNativeState copy() {
            XoshiroState copy = new XoshiroState(0);
            System.arraycopy(state, 0, copy.state, 0, 4);
            return copy;
        }
    }

    // ------------------------------------------------------------------ Secure

    static final class SecureState extends EngineState {
        static final SecureState INSTANCE = new SecureState();

        @Override
        public long nextValue() {
            byte[] b = HashFunctions.random().generateSeed(8);
            return littleEndian64(b, 0);
        }

        @Override
        public int chunkSize() {
            return 8;
        }

        @Override
        public byte[] nextBytes() {
            byte[] b = new byte[8];
            HashFunctions.random().nextBytes(b);
            return b;
        }

        @Override
        PhpArray stateArray() {
            return new PhpArray();
        }

        @Override
        void restore(PhpArray data) {
            // stateless
        }

        @Override
        public PhpNativeState copy() {
            return this;
        }

        @Override
        public Map<String, Object> debugProperties(PhpObject self) {
            return null;
        }
    }

    // ------------------------------------------------------------------ engine access

    /** Uniform view over a native engine object or a user class implementing Random\Engine. */
    static final class EngineAccess implements ChunkSource {
        private final PhpObject object;
        private final EngineState nativeState;
        private int lastSize;

        private EngineAccess(PhpObject object, EngineState nativeState) {
            this.object = object;
            this.nativeState = nativeState;
            this.lastSize = nativeState == null ? 8 : nativeState.chunkSize();
        }

        static EngineAccess of(PhpObject object) {
            if (object.nativeState() instanceof EngineState state) {
                return new EngineAccess(object, state);
            }
            if (object.phpClass().lowerName().equals("random\\engine\\secure")) {
                return new EngineAccess(object, SecureState.INSTANCE);
            }
            return new EngineAccess(object, null);
        }

        private byte[] userBytes() {
            byte[] raw = PhpStrings.bytes(callUserGenerate(object));
            if (raw.length == 0) {
                throw new PhpError(BROKEN_ENGINE, EMPTY_ENGINE_MESSAGE, null);
            }
            // PHP caps a user engine's chunk at 8 bytes and discards the tail.
            if (raw.length > 8) {
                raw = java.util.Arrays.copyOf(raw, 8);
            }
            lastSize = raw.length;
            return raw;
        }

        @Override
        public long nextValue() {
            if (nativeState != null) {
                return nativeState.nextValue();
            }
            byte[] raw = userBytes();
            return littleEndianAtMost8(raw);
        }

        Mt19937State legacyMt() {
            return nativeState instanceof Mt19937State mt && mt.mode == 1 ? mt : null;
        }

        @Override
        public int chunkSize() {
            return nativeState == null ? lastSize : nativeState.chunkSize();
        }

        @Override
        public byte[] nextBytes() {
            if (nativeState != null) {
                return nativeState.nextBytes();
            }
            return userBytes();
        }
    }

    private static String callUserGenerate(PhpObject engine) {
        PhpClass cls = engine.phpClass();
        PhpClass.MethodEntry entry = cls.lookupMethod("generate");
        if (entry == null || entry.callTarget() == null) {
            throw new PhpError("Random\\Randomizer::nextInt(): The engine does not implement Random\\Engine::generate()", null);
        }
        Object[] args = entry.fromTrait()
                ? new Object[]{engine, cls, entry.declaringClass()}
                : new Object[]{engine, cls};
        Object value = CallArgHelper.unwrapReturn(entry.callTarget().call(args));
        if (!(value instanceof String s)) {
            throw new PhpError(PhpError.TYPE_ERROR,
                    cls.name() + "::generate(): Return value must be of type string, "
                            + PhpOps.typeDescription(value == null ? PhpNull.INSTANCE : value) + " returned", null);
        }
        return s;
    }

    // ------------------------------------------------------------------ ranges

    private static long nextRaw(ChunkSource source, int wantedBytes) {
        long value = 0;
        int have = 0;
        while (have < wantedBytes) {
            long chunk = source.nextValue();
            int size = source.chunkSize();
            value |= chunk << (have * 8);
            have += size;
        }
        return wantedBytes == 4 ? value & 0xFFFFFFFFL : value;
    }

    private static PhpError brokenEngine() {
        return new PhpError(BROKEN_ENGINE, BROKEN_MESSAGE, null);
    }

    static long range32(ChunkSource source, long umax) {
        if (umax == 0xFFFFFFFFL) {
            return nextRaw(source, 4);
        }
        long span = umax + 1;
        if ((umax & span) == 0) {
            return nextRaw(source, 4) & umax;
        }
        long limit = 0xFFFFFFFEL - (0xFFFFFFFFL % span);
        for (int attempt = 0; attempt < 51; attempt++) {
            long value = nextRaw(source, 4);
            if (value <= limit) {
                return value % span;
            }
        }
        throw brokenEngine();
    }

    static long range64(ChunkSource source, long umax) {
        if (umax == -1L) {
            return nextRaw(source, 8);
        }
        long span = umax + 1;
        if ((umax & span) == 0) {
            return nextRaw(source, 8) & umax;
        }
        long limit = 0xFFFFFFFFFFFFFFFEL - Long.remainderUnsigned(-1L, span);
        for (int attempt = 0; attempt < 51; attempt++) {
            long value = nextRaw(source, 8);
            if (Long.compareUnsigned(value, limit) <= 0) {
                return Long.remainderUnsigned(value, span);
            }
        }
        throw brokenEngine();
    }

    static long range(ChunkSource source, long umax) {
        return (umax >>> 32) != 0 ? range64(source, umax) : range32(source, umax);
    }

    // ------------------------------------------------------------------ floats

    private static long intervalCount(double min, double max, double gamma) {
        double qmin = min / gamma;
        double qmax = max / gamma;
        double diff = qmax - qmin;
        double remainder;
        if (Math.abs(max) < Math.abs(min)) {
            remainder = qmax - (qmin + diff);
        } else {
            remainder = (-qmin) - (diff - qmax);
        }
        double ceiling = Math.ceil(diff);
        long count;
        if (ceiling >= 9223372036854775808.0) {
            double shifted = ceiling - 9223372036854775808.0;
            long converted = shifted >= 9223372036854775808.0 ? Long.MIN_VALUE : (long) shifted;
            count = converted ^ Long.MIN_VALUE;
        } else {
            count = (long) ceiling;
        }
        if (diff == ceiling && remainder > 0.0) {
            count++;
        }
        return count;
    }

    private static double addAt(double base, long index, double gamma) {
        long quotient = index >>> 2;
        long rest = index & 3;
        return (base * 0.25 + (double) quotient * gamma) * 4.0 + (double) rest * gamma;
    }

    private static double subAt(double base, long index, double gamma) {
        long quotient = index >>> 2;
        long rest = index & 3;
        return (base * 0.25 - (double) quotient * gamma) * 4.0 - (double) rest * gamma;
    }

    static final int CLOSED_OPEN = 0;
    static final int CLOSED_CLOSED = 1;
    static final int OPEN_CLOSED = 2;
    static final int OPEN_OPEN = 3;

    /** Returns NaN when the interval contains no representable float of the requested kind. */
    static double getFloat(ChunkSource engine, double min, double max, int boundary) {
        boolean nearMin = Math.abs(min) > Math.abs(max);
        double gamma = nearMin
                ? Math.nextAfter(min, Double.MAX_VALUE) - min
                : max - Math.nextAfter(max, -Double.MAX_VALUE);
        switch (boundary) {
            case CLOSED_OPEN: {
                long count = intervalCount(min, max, gamma);
                if (count == 0) {
                    return Double.NaN;
                }
                long draw = range64(engine, count - 1);
                if (nearMin) {
                    return addAt(min, draw, gamma);
                }
                long index = draw + 1;
                if (index == count) {
                    return min;
                }
                return subAt(max, index, gamma);
            }
            case CLOSED_CLOSED: {
                long count = intervalCount(min, max, gamma);
                long draw = range64(engine, count);
                if (draw == count) {
                    return nearMin ? max : min;
                }
                return nearMin ? addAt(min, draw, gamma) : subAt(max, draw, gamma);
            }
            case OPEN_CLOSED: {
                long count = intervalCount(min, max, gamma);
                if (count == 0) {
                    return Double.NaN;
                }
                long draw = range64(engine, count - 1);
                if (nearMin) {
                    if (draw == count - 1) {
                        return max;
                    }
                    return addAt(min, draw + 1, gamma);
                }
                return subAt(max, draw, gamma);
            }
            default: {
                long count = intervalCount(min, max, gamma);
                if (Long.compareUnsigned(count, 1L) <= 0) {
                    return Double.NaN;
                }
                long draw = range64(engine, count - 2);
                return nearMin ? addAt(min, draw + 1, gamma) : subAt(max, draw + 1, gamma);
            }
        }
    }

    static long dvalToLong(double value) {
        if (value >= 9223372036854775808.0) {
            double mod = value % 18446744073709551616.0;
            if (mod < 0) {
                mod += 18446744073709551616.0;
            }
            if (mod < 9223372036854775808.0) {
                return (long) mod;
            }
            return (long) (mod - 18446744073709551616.0);
        }
        if (value <= -9223372036854775808.0) {
            double mod = value % 18446744073709551616.0;
            if (mod < 0) {
                if (mod > -9223372036854775808.0) {
                    return (long) mod;
                }
                return (long) (mod + 18446744073709551616.0);
            }
            return (long) mod;
        }
        return (long) value;
    }

    static long scaleLegacy(long min, long max, long n) {
        double scaled = ((double) max - (double) min + 1.0) * ((double) n / 2147483648.0);
        return min + dvalToLong(scaled);
    }

    // ------------------------------------------------------------------ helper conversions

    static byte[] littleEndianBytes(long value) {
        return new byte[]{
                (byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24),
                (byte) (value >>> 32), (byte) (value >>> 40), (byte) (value >>> 48), (byte) (value >>> 56)
        };
    }

    static long littleEndian64(byte[] bytes, int offset) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= (bytes[offset + i] & 0xFFL) << (8 * i);
        }
        return v;
    }

    static int littleEndian32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16) | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static long littleEndianAtMost8(byte[] bytes) {
        long v = 0;
        int n = Math.min(bytes.length, 8);
        for (int i = 0; i < n; i++) {
            v |= (bytes[i] & 0xFFL) << (8 * i);
        }
        return v;
    }

    static String hexLittleEndian(long value) {
        return String.format("%016x", Long.reverseBytes(value));
    }

    private static BigInteger unsigned128(long high, long low) {
        return new BigInteger(Long.toUnsignedString(high)).shiftLeft(64)
                .add(new BigInteger(Long.toUnsignedString(low)));
    }

    private static long parseHex(String hex, int length, String cls) {
        if (hex.length() != length) {
            throw invalidSerialization(cls);
        }
        try {
            return Long.parseUnsignedLong(hex, 16);
        } catch (NumberFormatException e) {
            throw invalidSerialization(cls);
        }
    }

    private static long parseHexLittleEndian(Object value, String cls) {
        if (!(value instanceof String hex) || hex.length() != 16) {
            throw invalidSerialization(cls);
        }
        try {
            return Long.reverseBytes(Long.parseUnsignedLong(hex, 16));
        } catch (NumberFormatException e) {
            throw invalidSerialization(cls);
        }
    }

    static PhpError invalidSerialization(String cls) {
        return new PhpError("Exception", "Invalid serialization data for " + cls + " object", null);
    }

    private static int secureSeed32() {
        return HashFunctions.random().nextInt();
    }

    // ------------------------------------------------------------------ templates

    public static void buildTemplates(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        Map<String, PhpClassTemplate.MethodDecl> engineMethods = new LinkedHashMap<>();
        addAbstractMethod(language, engineMethods, "generate");
        register(out, new PhpClassTemplate(language.nextTemplateId(), ENGINE, true, true, false, false,
                null, List.of(), List.of(), List.of(), engineMethods));

        register(out, new PhpClassTemplate(language.nextTemplateId(), CRYPTO_ENGINE, true, true, false, false,
                null, List.of(ENGINE), List.of(), List.of(), Map.of()));

        register(out, buildMt19937(language));
        register(out, buildPcg(language));
        register(out, buildXoshiro(language));
        register(out, buildSecure(language));
        register(out, buildRandomizer(language));

        register(out, new PhpClassTemplate(language.nextTemplateId(), INTERVAL, false, false,
                false, false, false, null, List.<String>of(),
                List.<PhpClassTemplate.TraitUse>of(), List.<PhpClassTemplate.PropertyDecl>of(),
                List.<PhpClassTemplate.ConstantDecl>of(), Map.<String, PhpClassTemplate.MethodDecl>of(),
                Map.<String, PhpClassTemplate.MethodSignature>of(), List.of(), true, null,
                List.of(new PhpClassTemplate.EnumCaseDecl("ClosedOpen", null, null, List.of()),
                        new PhpClassTemplate.EnumCaseDecl("ClosedClosed", null, null, List.of()),
                        new PhpClassTemplate.EnumCaseDecl("OpenClosed", null, null, List.of()),
                        new PhpClassTemplate.EnumCaseDecl("OpenOpen", null, null, List.of()))));
    }

    private static void register(Map<String, PhpClassTemplate> out, PhpClassTemplate template) {
        out.put(template.lowerName(), template);
    }

    private static void addAbstractMethod(PhpLanguage language,
                                          Map<String, PhpClassTemplate.MethodDecl> map, String name) {
        String lower = name.toLowerCase();
        map.put(lower, new PhpClassTemplate.MethodDecl(
                name, lower, language.methodId(name), Visibility.PUBLIC, false, true, false, null));
    }

    private static void addMethod(PhpLanguage language,
                                  Map<String, PhpClassTemplate.MethodDecl> map,
                                  String name, dev.phpruntime.runtime.PhpExceptions.BuiltinMethod impl) {
        addMethod(language, map, name, Visibility.PUBLIC, impl);
    }

    private static void addMethod(PhpLanguage language,
                                  Map<String, PhpClassTemplate.MethodDecl> map,
                                  String name, Visibility visibility,
                                  dev.phpruntime.runtime.PhpExceptions.BuiltinMethod impl) {
        String lower = name.toLowerCase();
        var root = new dev.phpruntime.runtime.PhpExceptions.BuiltinMethodRootNode(language, name, impl);
        map.put(lower, new PhpClassTemplate.MethodDecl(
                name, lower, language.methodId(name), visibility, false, false, false, root.getCallTarget()));
    }

    private static PhpClassTemplate buildMt19937(PhpLanguage language) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        addMethod(language, methods, "__construct", (ctx, self, args) -> {
            requireAtMost("Random\\Engine\\Mt19937::__construct", args, 2);
            Object seedArg = args.length > 0 ? args[0] : PhpNull.INSTANCE;
            long mode = args.length > 1 ? intArg(args[1], "Random\\Engine\\Mt19937::__construct", 2, "mode") : 0;
            int seed;
            if (seedArg == PhpNull.INSTANCE) {
                seed = secureSeed32();
            } else {
                seed = (int) intLike(seedArg, "Random\\Engine\\Mt19937::__construct", "seed", "?int");
            }
            if (mode != 0 && mode != 1) {
                throw new PhpError(PhpError.VALUE_ERROR,
                        "Random\\Engine\\Mt19937::__construct(): Argument #2 ($mode) must be either MT_RAND_MT19937 or MT_RAND_PHP", null);
            }
            self.setNativeState(new Mt19937State(seed, (int) mode));
            return PhpNull.INSTANCE;
        });
        addMethod(language, methods, "generate", (ctx, self, args) -> {
            requireExactly("Random\\Engine\\Mt19937::generate", args, 0);
            return PhpStrings.of(mt(self).nextBytes());
        });
        addMethod(language, methods, "__serialize", (ctx, self, args) -> {
            requireExactly("Random\\Engine\\Mt19937::__serialize", args, 0);
            return serializeEnvelope(mt(self).stateArray());
        });
        addMethod(language, methods, "__unserialize", (ctx, self, args) -> {
            requireExactly("Random\\Engine\\Mt19937::__unserialize", args, 1);
            PhpArray state = envelopeState(arrayArg(args[0], "Random\\Engine\\Mt19937::__unserialize",
                    1, "data"), MT19937);
            Mt19937State restored = new Mt19937State(0, 0);
            restored.restore(state);
            self.setNativeState(restored);
            return PhpNull.INSTANCE;
        });
        addMethod(language, methods, "__debugInfo", (ctx, self, args) -> {
            requireExactly("Random\\Engine\\Mt19937::__debugInfo", args, 0);
            return debugArray(mt(self));
        });
        return new PhpClassTemplate(language.nextTemplateId(), MT19937, false, false, true, false,
                null, List.of(ENGINE), List.of(), List.of(), methods);
    }

    private static PhpClassTemplate buildPcg(PhpLanguage language) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        addMethod(language, methods, "__construct", (ctx, self, args) -> {
            requireAtMost("Random\\Engine\\PcgOneseq128XslRr64::__construct", args, 1);
            Object seedArg = args.length > 0 ? args[0] : PhpNull.INSTANCE;
            PcgState state;
            if (seedArg == PhpNull.INSTANCE) {
                byte[] bytes = new byte[16];
                HashFunctions.random().nextBytes(bytes);
                state = new PcgState(bytes);
            } else if (seedArg instanceof Long l) {
                state = new PcgState(l);
            } else if (seedArg instanceof Double d) {
                state = new PcgState((long) (double) d);
            } else if (seedArg instanceof Boolean b) {
                state = new PcgState(b ? 1L : 0L);
            } else if (seedArg instanceof String
                    || (seedArg instanceof PhpObject object && object.phpClass().toStringMethod() != null)) {
                byte[] bytes = PhpStrings.bytes(PhpOps.toStr(seedArg));
                if (bytes.length != 16) {
                    throw new PhpError(PhpError.VALUE_ERROR,
                            "Random\\Engine\\PcgOneseq128XslRr64::__construct(): Argument #1 ($seed) must be a 16 byte (128 bit) string", null);
                }
                state = new PcgState(bytes);
            } else {
                throw typeError("Random\\Engine\\PcgOneseq128XslRr64::__construct", 1, "seed",
                        "string|int|null", seedArg);
            }
            self.setNativeState(state);
            return PhpNull.INSTANCE;
        });
        addMethod(language, methods, "generate", (ctx, self, args) -> {
            requireExactly("Random\\Engine\\PcgOneseq128XslRr64::generate", args, 0);
            return PhpStrings.of(pcg(self).nextBytes());
        });
        addMethod(language, methods, "jump", (ctx, self, args) -> {
            requireExactly("Random\\Engine\\PcgOneseq128XslRr64::jump", args, 1);
            long advance = intArg(args[0], "Random\\Engine\\PcgOneseq128XslRr64::jump", 1, "advance");
            if (advance < 0) {
                throw new PhpError(PhpError.VALUE_ERROR,
                        "Random\\Engine\\PcgOneseq128XslRr64::jump(): Argument #1 ($advance) must be greater than or equal to 0", null);
            }
            pcg(self).jump(advance);
            return PhpNull.INSTANCE;
        });
        addMethod(language, methods, "__serialize", (ctx, self, args) -> {
            requireExactly("Random\\Engine\\PcgOneseq128XslRr64::__serialize", args, 0);
            return serializeEnvelope(pcg(self).stateArray());
        });
        addMethod(language, methods, "__unserialize", (ctx, self, args) -> {
            requireExactly("Random\\Engine\\PcgOneseq128XslRr64::__unserialize", args, 1);
            PhpArray state = envelopeState(arrayArg(args[0], "Random\\Engine\\PcgOneseq128XslRr64::__unserialize",
                    1, "data"), PCG);
            PcgState restored = new PcgState(0);
            restored.restore(state);
            self.setNativeState(restored);
            return PhpNull.INSTANCE;
        });
        addMethod(language, methods, "__debugInfo", (ctx, self, args) -> {
            requireExactly("Random\\Engine\\PcgOneseq128XslRr64::__debugInfo", args, 0);
            return debugArray(pcg(self));
        });
        return new PhpClassTemplate(language.nextTemplateId(), PCG, false, false, true, false,
                null, List.of(ENGINE), List.of(), List.of(), methods);
    }

    private static PhpClassTemplate buildXoshiro(PhpLanguage language) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        addMethod(language, methods, "__construct", (ctx, self, args) -> {
            requireAtMost("Random\\Engine\\Xoshiro256StarStar::__construct", args, 1);
            Object seedArg = args.length > 0 ? args[0] : PhpNull.INSTANCE;
            XoshiroState state;
            if (seedArg == PhpNull.INSTANCE) {
                byte[] bytes = new byte[32];
                HashFunctions.random().nextBytes(bytes);
                state = new XoshiroState(bytes);
            } else if (seedArg instanceof Long l) {
                state = new XoshiroState(l);
            } else if (seedArg instanceof Double d) {
                state = new XoshiroState((long) (double) d);
            } else if (seedArg instanceof Boolean b) {
                state = new XoshiroState(b ? 1L : 0L);
            } else if (seedArg instanceof String
                    || (seedArg instanceof PhpObject object && object.phpClass().toStringMethod() != null)) {
                byte[] bytes = PhpStrings.bytes(PhpOps.toStr(seedArg));
                if (bytes.length != 32) {
                    throw new PhpError(PhpError.VALUE_ERROR,
                            "Random\\Engine\\Xoshiro256StarStar::__construct(): Argument #1 ($seed) must be a 32 byte (256 bit) string", null);
                }
                boolean allZero = true;
                for (byte b : bytes) {
                    if (b != 0) {
                        allZero = false;
                        break;
                    }
                }
                if (allZero) {
                    throw new PhpError(PhpError.VALUE_ERROR,
                            "Random\\Engine\\Xoshiro256StarStar::__construct(): Argument #1 ($seed) must not consist entirely of NUL bytes", null);
                }
                state = new XoshiroState(bytes);
            } else {
                throw typeError("Random\\Engine\\Xoshiro256StarStar::__construct", 1, "seed",
                        "string|int|null", seedArg);
            }
            self.setNativeState(state);
            return PhpNull.INSTANCE;
        });
        addMethod(language, methods, "generate", (ctx, self, args) -> {
            requireExactly("Random\\Engine\\Xoshiro256StarStar::generate", args, 0);
            return PhpStrings.of(xoshiro(self).nextBytes());
        });
        addMethod(language, methods, "jump", (ctx, self, args) -> {
            requireExactly("Random\\Engine\\Xoshiro256StarStar::jump", args, 0);
            xoshiro(self).jump();
            return PhpNull.INSTANCE;
        });
        addMethod(language, methods, "jumpLong", (ctx, self, args) -> {
            requireExactly("Random\\Engine\\Xoshiro256StarStar::jumpLong", args, 0);
            xoshiro(self).jumpLong();
            return PhpNull.INSTANCE;
        });
        addMethod(language, methods, "__serialize", (ctx, self, args) -> {
            requireExactly("Random\\Engine\\Xoshiro256StarStar::__serialize", args, 0);
            return serializeEnvelope(xoshiro(self).stateArray());
        });
        addMethod(language, methods, "__unserialize", (ctx, self, args) -> {
            requireExactly("Random\\Engine\\Xoshiro256StarStar::__unserialize", args, 1);
            PhpArray state = envelopeState(arrayArg(args[0], "Random\\Engine\\Xoshiro256StarStar::__unserialize",
                    1, "data"), XOSHIRO);
            XoshiroState restored = new XoshiroState(0);
            restored.restore(state);
            self.setNativeState(restored);
            return PhpNull.INSTANCE;
        });
        addMethod(language, methods, "__debugInfo", (ctx, self, args) -> {
            requireExactly("Random\\Engine\\Xoshiro256StarStar::__debugInfo", args, 0);
            return debugArray(xoshiro(self));
        });
        return new PhpClassTemplate(language.nextTemplateId(), XOSHIRO, false, false, true, false,
                null, List.of(ENGINE), List.of(), List.of(), methods);
    }

    private static PhpClassTemplate buildSecure(PhpLanguage language) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        addMethod(language, methods, "generate", (ctx, self, args) -> {
            requireExactly("Random\\Engine\\Secure::generate", args, 0);
            byte[] bytes = new byte[8];
            HashFunctions.random().nextBytes(bytes);
            return PhpStrings.of(bytes);
        });
        addMethod(language, methods, "__clone", Visibility.PRIVATE,
                (ctx, self, args) -> uncloneable(self));
        return new PhpClassTemplate(language.nextTemplateId(), SECURE, false, false, true, false,
                null, List.of(CRYPTO_ENGINE, ENGINE), List.of(), List.of(), methods);
    }

    private static Object uncloneable(PhpObject self) {
        throw new PhpError("Trying to clone an uncloneable object of class " + self.phpClass().name(), null);
    }

    private static PhpClassTemplate buildRandomizer(PhpLanguage language) {
        List<PhpClassTemplate.PropertyDecl> properties = List.of(
                new PhpClassTemplate.PropertyDecl("engine", Visibility.PUBLIC, false, true,
                        PhpNull.INSTANCE, null, new dev.phpruntime.runtime.PhpType.Named(ENGINE), List.of()));

        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        addMethod(language, methods, "__construct", (ctx, self, args) -> {
            requireAtMost("Random\\Randomizer::__construct", args, 1);
            Object arg = args.length > 0 ? args[0] : PhpNull.INSTANCE;
            PhpObject engine;
            if (arg == PhpNull.INSTANCE) {
                engine = newSecureEngine(ctx);
            } else if (arg instanceof PhpObject object && isEngine(ctx, object)) {
                engine = object;
            } else {
                throw typeError("Random\\Randomizer::__construct", 1, "engine", "?Random\\Engine", arg);
            }
            setEngine(self, engine);
            return PhpNull.INSTANCE;
        });
        addMethod(language, methods, "nextInt", (ctx, self, args) -> {
            requireExactly("Random\\Randomizer::nextInt", args, 0);
            return EngineAccess.of(engineOf(self)).nextValue() >>> 1;
        });
        addMethod(language, methods, "getInt", (ctx, self, args) -> {
            requireExactly("Random\\Randomizer::getInt", args, 2);
            long min = intArg(args[0], "Random\\Randomizer::getInt", 1, "min");
            long max = intArg(args[1], "Random\\Randomizer::getInt", 2, "max");
            if (max < min) {
                throw new PhpError(PhpError.VALUE_ERROR,
                        "Random\\Randomizer::getInt(): Argument #2 ($max) must be greater than or equal to argument #1 ($min)", null);
            }
            EngineAccess engine = EngineAccess.of(engineOf(self));
            if (engine.legacyMt() != null) {
                long n = engine.legacyMt().nextValue() >>> 1;
                return scaleLegacy(min, max, n);
            }
            long umax = max - min;
            return min + range(engine, umax);
        });
        addMethod(language, methods, "nextFloat", (ctx, self, args) -> {
            requireExactly("Random\\Randomizer::nextFloat", args, 0);
            long value = nextRaw(EngineAccess.of(engineOf(self)), 8);
            return (double) (value >>> 11) * 0x1.0p-53;
        });
        addMethod(language, methods, "getFloat", (ctx, self, args) -> {
            requireAtLeast("Random\\Randomizer::getFloat", args, 2);
            requireAtMost("Random\\Randomizer::getFloat", args, 3);
            double min = floatArg(args[0], "Random\\Randomizer::getFloat", 1, "min");
            double max = floatArg(args[1], "Random\\Randomizer::getFloat", 2, "max");
            int boundary = CLOSED_OPEN;
            if (args.length > 2) {
                boundary = intervalBoundary(args[2]);
            }
            if (!Double.isFinite(min)) {
                throw new PhpError(PhpError.VALUE_ERROR,
                        "Random\\Randomizer::getFloat(): Argument #1 ($min) must be finite", null);
            }
            if (!Double.isFinite(max)) {
                throw new PhpError(PhpError.VALUE_ERROR,
                        "Random\\Randomizer::getFloat(): Argument #2 ($max) must be finite", null);
            }
            if (boundary == CLOSED_CLOSED) {
                if (max < min) {
                    throw new PhpError(PhpError.VALUE_ERROR,
                            "Random\\Randomizer::getFloat(): Argument #2 ($max) must be greater than or equal to argument #1 ($min)", null);
                }
            } else if (!(max > min)) {
                throw new PhpError(PhpError.VALUE_ERROR,
                        "Random\\Randomizer::getFloat(): Argument #2 ($max) must be greater than argument #1 ($min)", null);
            }
            double result = getFloat(EngineAccess.of(engineOf(self)), min, max, boundary);
            if (Double.isNaN(result)) {
                throw new PhpError(PhpError.VALUE_ERROR,
                        "The given interval is empty, there are no floats between argument #1 ($min) and argument #2 ($max)", null);
            }
            return result;
        });
        addMethod(language, methods, "getBytes", (ctx, self, args) -> {
            requireExactly("Random\\Randomizer::getBytes", args, 1);
            long length = intArg(args[0], "Random\\Randomizer::getBytes", 1, "length");
            if (length < 1) {
                throw new PhpError(PhpError.VALUE_ERROR,
                        "Random\\Randomizer::getBytes(): Argument #1 ($length) must be greater than 0", null);
            }
            EngineAccess engine = EngineAccess.of(engineOf(self));
            byte[] out = new byte[(int) length];
            int filled = 0;
            while (filled < out.length) {
                byte[] chunk = engine.nextBytes();
                int n = Math.min(chunk.length, out.length - filled);
                System.arraycopy(chunk, 0, out, filled, n);
                filled += n;
            }
            return PhpStrings.of(out);
        });
        addMethod(language, methods, "getBytesFromString", (ctx, self, args) -> {
            requireExactly("Random\\Randomizer::getBytesFromString", args, 2);
            String source = stringArg(args[0], "Random\\Randomizer::getBytesFromString", 1, "string");
            long length = intArg(args[1], "Random\\Randomizer::getBytesFromString", 2, "length");
            if (source.isEmpty()) {
                throw new PhpError(PhpError.VALUE_ERROR,
                        "Random\\Randomizer::getBytesFromString(): Argument #1 ($string) must not be empty", null);
            }
            if (length < 1) {
                throw new PhpError(PhpError.VALUE_ERROR,
                        "Random\\Randomizer::getBytesFromString(): Argument #2 ($length) must be greater than 0", null);
            }
            EngineAccess engine = EngineAccess.of(engineOf(self));
            byte[] chars = PhpStrings.bytes(source);
            StringBuilder out = new StringBuilder((int) length);
            if (chars.length <= 256) {
                int mask = chars.length == 1 ? 0 : (Integer.highestOneBit(chars.length - 1) << 1) - 1;
                byte[] buffer = new byte[0];
                int pos = 0;
                int rejected = 0;
                while (out.length() < length) {
                    if (pos >= buffer.length) {
                        buffer = engine.nextBytes();
                        pos = 0;
                    }
                    int value = (buffer[pos++] & 0xFF) & mask;
                    if (value >= chars.length) {
                        if (++rejected > 50) {
                            throw brokenEngine();
                        }
                        continue;
                    }
                    rejected = 0;
                    out.append((char) (chars[value] & 0xFF));
                }
            } else {
                for (long i = 0; i < length; i++) {
                    long index = range(engine, chars.length - 1L);
                    out.append((char) (chars[(int) index] & 0xFF));
                }
            }
            return out.toString();
        });
        addMethod(language, methods, "shuffleArray", (ctx, self, args) -> {
            requireExactly("Random\\Randomizer::shuffleArray", args, 1);
            PhpArray array = requireArray(args[0], "Random\\Randomizer::shuffleArray");
            List<Object> values = new java.util.ArrayList<>(array.values());
            shuffleValues(EngineAccess.of(engineOf(self)), values);
            return PhpArray.ofList(values);
        });
        addMethod(language, methods, "shuffleBytes", (ctx, self, args) -> {
            requireExactly("Random\\Randomizer::shuffleBytes", args, 1);
            byte[] bytes = PhpStrings.bytes(stringArg(args[0], "Random\\Randomizer::shuffleBytes", 1, "bytes"));
            EngineAccess engine = EngineAccess.of(engineOf(self));
            for (int i = bytes.length - 1; i > 0; i--) {
                long j = range(engine, i);
                byte tmp = bytes[i];
                bytes[i] = bytes[(int) j];
                bytes[(int) j] = tmp;
            }
            return PhpStrings.of(bytes);
        });
        addMethod(language, methods, "pickArrayKeys", (ctx, self, args) -> {
            requireExactly("Random\\Randomizer::pickArrayKeys", args, 2);
            PhpArray array = requireArray(args[0], "Random\\Randomizer::pickArrayKeys");
            long num = intArg(args[1], "Random\\Randomizer::pickArrayKeys", 2, "num");
            if (array.size() == 0) {
                throw new PhpError(PhpError.VALUE_ERROR,
                        "Random\\Randomizer::pickArrayKeys(): Argument #1 ($array) must not be empty", null);
            }
            if (num < 1 || num > array.size()) {
                throw new PhpError(PhpError.VALUE_ERROR,
                        "Random\\Randomizer::pickArrayKeys(): Argument #2 ($num) must be between 1 and the number of elements in argument #1 ($array)", null);
            }
            return pickKeys(EngineAccess.of(engineOf(self)), array, (int) num);
        });
        addMethod(language, methods, "__serialize", (ctx, self, args) -> {
            requireExactly("Random\\Randomizer::__serialize", args, 0);
            PhpArray props = new PhpArray();
            props.put("engine", engineOf(self));
            PhpArray out = new PhpArray();
            out.put(0L, props);
            return out;
        });
        addMethod(language, methods, "__unserialize", (ctx, self, args) -> {
            requireExactly("Random\\Randomizer::__unserialize", args, 1);
            PhpArray data = arrayArg(args[0], "Random\\Randomizer::__unserialize", 1, "data");
            if (data.size() != 1) {
                throw invalidSerialization(RANDOMIZER);
            }
            Object props = data.get(0L);
            if (!(props instanceof PhpArray propertyArray)) {
                throw invalidSerialization(RANDOMIZER);
            }
            Object engine = propertyArray.get("engine");
            if (!(engine instanceof PhpObject object) || !isEngine(ctx, object)) {
                throw invalidSerialization(RANDOMIZER);
            }
            setEngine(self, object);
            return PhpNull.INSTANCE;
        });
        addMethod(language, methods, "__clone", Visibility.PRIVATE,
                (ctx, self, args) -> uncloneable(self));
        addMethod(language, methods, "__set", (ctx, self, args) -> {
            throw new PhpError("Cannot create dynamic property Random\\Randomizer::$"
                    + PhpOps.toStr(args[0]), null);
        });
        return new PhpClassTemplate(language.nextTemplateId(), RANDOMIZER, false, false, true, false,
                null, List.of(), properties, List.of(), methods);
    }

    static void shuffleValues(ChunkSource engine, List<Object> values) {
        for (int i = values.size() - 1; i > 0; i--) {
            long j = range(engine, i);
            java.util.Collections.swap(values, i, (int) j);
        }
    }

    static PhpArray pickKeys(ChunkSource engine, PhpArray array, int num) {
        List<Object> keys = array.keys();
        int count = keys.size();
        if (count == 1) {
            // PHP takes a draw even though there is only one key to return.
            range(engine, 0L);
            return PhpArray.ofList(keys);
        }
        if (num == count) {
            return PhpArray.ofList(keys);
        }
        boolean selecting = num <= count / 2;
        int target = selecting ? num : count - num;
        boolean[] picked = new boolean[count];
        int chosen = 0;
        while (chosen < target) {
            int attempts = 0;
            while (true) {
                long index = range(engine, count - 1L);
                if (!picked[(int) index]) {
                    picked[(int) index] = true;
                    chosen++;
                    break;
                }
                if (++attempts > 50) {
                    throw brokenEngine();
                }
            }
        }
        PhpArray out = new PhpArray();
        for (int i = 0; i < count; i++) {
            if (selecting == picked[i]) {
                out.append(keys.get(i));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ object helpers

    private static Mt19937State mt(PhpObject self) {
        if (self != null && self.nativeState() instanceof Mt19937State state) {
            return state;
        }
        throw new PhpError("Random\\Engine\\Mt19937 object is not initialized", null);
    }

    private static PcgState pcg(PhpObject self) {
        if (self != null && self.nativeState() instanceof PcgState state) {
            return state;
        }
        throw new PhpError("Random\\Engine\\PcgOneseq128XslRr64 object is not initialized", null);
    }

    private static XoshiroState xoshiro(PhpObject self) {
        if (self != null && self.nativeState() instanceof XoshiroState state) {
            return state;
        }
        throw new PhpError("Random\\Engine\\Xoshiro256StarStar object is not initialized", null);
    }

    private static PhpObject engineOf(PhpObject randomizer) {
        Object value = DynamicObject.GetNode.getUncached().execute(randomizer, "engine", PhpNull.INSTANCE);
        if (value instanceof PhpObject engine) {
            return engine;
        }
        throw new PhpError("Random\\Randomizer object is not initialized", null);
    }

    private static void setEngine(PhpObject randomizer, PhpObject engine) {
        DynamicObject.PutNode.getUncached().execute(randomizer, "engine", engine);
    }

    static PhpObject newSecureEngine(PhpContext ctx) {
        PhpClass cls = ctx.lookupClass(SECURE);
        PhpObject object = new PhpObject(cls, ctx.nextObjectId());
        cls.populateDefaults(object);
        return object;
    }

    private static boolean isEngine(PhpContext ctx, PhpObject object) {
        PhpClass engine = ctx.lookupClass(ENGINE);
        return engine != null && object.phpClass().isInstanceOf(engine);
    }

    private static PhpArray serializeEnvelope(PhpArray state) {
        PhpArray out = new PhpArray();
        out.put(0L, new PhpArray());
        out.put(1L, state);
        return out;
    }

    private static PhpArray envelopeState(Object value, String cls) {
        if (!(value instanceof PhpArray data) || data.size() != 2) {
            throw invalidSerialization(cls);
        }
        Object state = data.get(1L);
        if (!(state instanceof PhpArray array)) {
            throw invalidSerialization(cls);
        }
        return array;
    }

    private static PhpArray debugArray(EngineState state) {
        PhpArray out = new PhpArray();
        out.put("__states", state.stateArray());
        return out;
    }

    // ------------------------------------------------------------------ argument checks

    static void requireExactly(String method, Object[] args, int count) {
        if (args.length != count) {
            throw new PhpError(PhpError.ARGUMENT_COUNT,
                    method + "() expects exactly " + count + " argument" + (count == 1 ? "" : "s")
                            + ", " + args.length + " given", null);
        }
    }

    static void requireAtLeast(String method, Object[] args, int count) {
        if (args.length < count) {
            throw new PhpError(PhpError.ARGUMENT_COUNT,
                    method + "() expects at least " + count + " arguments, " + args.length + " given", null);
        }
    }

    static void requireAtMost(String method, Object[] args, int count) {
        if (args.length > count) {
            throw new PhpError(PhpError.ARGUMENT_COUNT,
                    method + "() expects at most " + count + " argument" + (count == 1 ? "" : "s")
                            + ", " + args.length + " given", null);
        }
    }

    static long intLike(Object value, String method, String name, String expected) {
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Double d) {
            return (long) (double) d;
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (value instanceof String s && PhpOps.isNumericString(s)) {
            return PhpOps.toLong(value);
        }
        throw typeError(method, 1, name, expected, value);
    }

    static long intArg(Object value, String method, int position, String name) {
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Double d) {
            return (long) (double) d;
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (value == PhpNull.INSTANCE) {
            return 0;
        }
        if (value instanceof String s) {
            if (PhpOps.isNumericString(s)) {
                return PhpOps.toLong(value);
            }
        }
        throw typeError(method, position, name, "int", value);
    }

    static String stringArg(Object value, String method, int position, String name) {
        if (value instanceof String s) {
            return s;
        }
        if (value == PhpNull.INSTANCE) {
            return "";
        }
        if ((value instanceof Long || value instanceof Double || value instanceof Boolean)
                || (value instanceof PhpObject object && object.phpClass().toStringMethod() != null)) {
            return PhpOps.toStr(value);
        }
        throw typeError(method, position, name, "string", value);
    }

    static double floatArg(Object value, String method, int position, String name) {
        if (value instanceof Double d) {
            return d;
        }
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Boolean b) {
            return b ? 1.0 : 0.0;
        }
        if (value instanceof String s && PhpOps.isNumericString(s)) {
            return PhpOps.toDouble(value);
        }
        throw typeError(method, position, name, "float", value);
    }

    private static int intervalBoundary(Object value) {
        if (value instanceof PhpObject object && object.nativeState() instanceof EnumCaseState state
                && state.enumName().equalsIgnoreCase(INTERVAL)) {
            switch (state.caseName()) {
                case "ClosedOpen":
                    return CLOSED_OPEN;
                case "ClosedClosed":
                    return CLOSED_CLOSED;
                case "OpenClosed":
                    return OPEN_CLOSED;
                case "OpenOpen":
                    return OPEN_OPEN;
                default:
                    break;
            }
        }
        throw typeError("Random\\Randomizer::getFloat", 3, "boundary", "Random\\IntervalBoundary", value);
    }

    static PhpArray requireArray(Object value, String method) {
        return arrayArg(value, method, 1, "array");
    }

    static PhpArray arrayArg(Object value, String method, int position, String name) {
        if (value instanceof PhpArray array) {
            return array;
        }
        throw new PhpError(PhpError.TYPE_ERROR,
                method + "(): Argument #" + position + " ($" + name + ") must be of type array, "
                        + PhpOps.typeDescription(value) + " given", null);
    }

    static PhpError typeError(String method, int position, String name, String expected, Object value) {
        return new PhpError(PhpError.TYPE_ERROR,
                method + "(): Argument #" + position + " ($" + name + ") must be of type " + expected + ", "
                        + PhpOps.typeDescription(value) + " given", null);
    }
}
