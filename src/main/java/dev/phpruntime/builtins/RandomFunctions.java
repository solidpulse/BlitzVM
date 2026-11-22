package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpStrings;

import java.util.ArrayList;
import java.util.List;

/** The seeded procedural random functions, backed by the same engines as Random\Randomizer. */
final class RandomFunctions {

    private RandomFunctions() {
    }

    /** Per-request global generator state. */
    static final class Global {
        RandomClasses.Mt19937State engine;
        int lcgS1;
        int lcgS2;
        boolean lcgSeeded;
    }

    static Global state(PhpContext ctx) {
        ContextState context = ContextState.of(ctx);
        Global global = context.random;
        if (global == null) {
            global = new Global();
            context.random = global;
        }
        return global;
    }

    private static final class GlobalSource implements RandomClasses.ChunkSource {
        private final Global global;

        GlobalSource(Global global) {
            this.global = global;
        }

        @Override
        public long nextValue() {
            return engine(global).nextValue();
        }

        @Override
        public int chunkSize() {
            return 4;
        }

        @Override
        public byte[] nextBytes() {
            return engine(global).nextBytes();
        }
    }

    private static RandomClasses.Mt19937State engine(Global global) {
        if (global.engine == null) {
            global.engine = new RandomClasses.Mt19937State(HashFunctions.random().nextInt(), 0);
        }
        return global.engine;
    }

    static void register() {
        Builtins.def("mt_srand", 0, 2, (ctx, a) -> srand(ctx, a, "mt_srand"));
        Builtins.def("srand", 0, 2, (ctx, a) -> srand(ctx, a, "srand"));
        Builtins.def("mt_rand", 0, -1, (ctx, a) -> rand(ctx, a, "mt_rand", false));
        Builtins.def("rand", 0, -1, (ctx, a) -> rand(ctx, a, "rand", true));
        Builtins.def("lcg_value", 0, 0, (ctx, a) -> lcgValue(ctx));
        Builtins.def("mt_getrandmax", 0, 0, (ctx, a) -> 2147483647L);
        Builtins.def("getrandmax", 0, 0, (ctx, a) -> 2147483647L);
        Builtins.def("str_shuffle", 1, 1, (ctx, a) -> {
            byte[] bytes = PhpStrings.bytes(RandomClasses.stringArg(a[0], "str_shuffle", 1, "string"));
            List<Object> values = new ArrayList<>(bytes.length);
            for (byte b : bytes) {
                values.add((long) (b & 0xFF));
            }
            RandomClasses.shuffleValues(new GlobalSource(state(ctx)), values);
            byte[] out = new byte[bytes.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) (long) (Long) values.get(i);
            }
            return PhpStrings.of(out);
        });
    }

    private static Object srand(PhpContext ctx, Object[] args, String name) {
        Global global = state(ctx);
        int seed;
        Object seedArg = args.length > 0 ? args[0] : PhpNull.INSTANCE;
        if (seedArg == PhpNull.INSTANCE) {
            seed = HashFunctions.random().nextInt();
        } else {
            seed = (int) RandomClasses.intLike(seedArg, name, "seed", "?int");
        }
        long mode = args.length > 1 ? RandomClasses.intArg(args[1], name, 2, "mode") : 0;
        global.engine = new RandomClasses.Mt19937State(seed, mode == 1 ? 1 : 0);
        global.lcgSeeded = false;
        return PhpNull.INSTANCE;
    }

    private static Object rand(PhpContext ctx, Object[] args, String name, boolean legacySwap) {
        Global global = state(ctx);
        RandomClasses.Mt19937State engine = engine(global);
        if (args.length == 0) {
            return engine.nextValue() >>> 1;
        }
        if (args.length != 2) {
            throw new PhpError(PhpError.ARGUMENT_COUNT,
                    name + "() expects exactly 2 arguments, " + args.length + " given", null);
        }
        long min = RandomClasses.intArg(args[0], name, 1, "min");
        long max = RandomClasses.intArg(args[1], name, 2, "max");
        if (max < min) {
            if (!legacySwap) {
                throw new PhpError(PhpError.VALUE_ERROR,
                        name + "(): Argument #2 ($max) must be greater than or equal to argument #1 ($min)", null);
            }
            long swap = min;
            min = max;
            max = swap;
        }
        if (engine.mode == 1) {
            return RandomClasses.scaleLegacy(min, max, engine.nextValue() >>> 1);
        }
        long umax = max - min;
        return min + RandomClasses.range(new GlobalSource(global), umax);
    }

    private static Object lcgValue(PhpContext ctx) {
        Global global = state(ctx);
        if (!global.lcgSeeded) {
            byte[] seed = new byte[8];
            HashFunctions.random().nextBytes(seed);
            global.lcgS1 = RandomClasses.littleEndian32(seed, 0);
            global.lcgS2 = RandomClasses.littleEndian32(seed, 4);
            global.lcgSeeded = true;
        }
        int s1 = global.lcgS1;
        int s2 = global.lcgS2;
        int q = s1 / 53668;
        int v = (s1 - q * 53668) * 40014 - q * 12211;
        if (v < 0) {
            v += 2147483563;
        }
        s1 = v;
        q = s2 / 52774;
        v = (s2 - q * 52774) * 40692 - q * 3791;
        if (v < 0) {
            v += 2147483399;
        }
        s2 = v;
        int z = s1 - s2;
        if (z <= 0) {
            z += 2147483562;
        }
        global.lcgS1 = s1;
        global.lcgS2 = s2;
        return z * 4.656613e-10;
    }

    static void shuffleValues(PhpContext ctx, List<Object> values) {
        RandomClasses.shuffleValues(new GlobalSource(state(ctx)), values);
    }

    static Object arrayRand(PhpContext ctx, Object[] args) {
        if (!(args[0] instanceof PhpArray array)) {
            throw new PhpError(PhpError.TYPE_ERROR,
                    "array_rand(): Argument #1 ($array) must be of type array, "
                            + PhpOps.typeDescription(args[0]) + " given", null);
        }
        long num = args.length > 1 ? RandomClasses.intArg(args[1], "array_rand", 2, "num") : 1;
        if (array.size() == 0) {
            throw new PhpError(PhpError.VALUE_ERROR,
                    "array_rand(): Argument #1 ($array) must not be empty", null);
        }
        if (num < 1 || num > array.size()) {
            throw new PhpError(PhpError.VALUE_ERROR,
                    "array_rand(): Argument #2 ($num) must be between 1 and the number of elements in argument #1 ($array)", null);
        }
        GlobalSource source = new GlobalSource(state(ctx));
        PhpArray keys = RandomClasses.pickKeys(source, array, (int) num);
        if (num == 1) {
            return keys.get(0L);
        }
        return keys;
    }
}
