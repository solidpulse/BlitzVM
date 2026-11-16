package dev.phpruntime.builtins;

import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpStrings;
import dev.phpruntime.runtime.PhpObject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

/** hash, hash_hmac, hash_equals, hash_algos, hex2bin. */
final class HashFunctions {

    /** PHP algorithm name -> Java MessageDigest name and HMAC block size, in PHP's hash_algos() order. */
    private record Algo(String javaName, int blockSize) {
    }

    private static final Map<String, Algo> ALGOS = new LinkedHashMap<>();

    static {
        ALGOS.put("md5", new Algo("MD5", 64));
        ALGOS.put("sha1", new Algo("SHA-1", 64));
        ALGOS.put("sha224", new Algo("SHA-224", 64));
        ALGOS.put("sha256", new Algo("SHA-256", 64));
        ALGOS.put("sha384", new Algo("SHA-384", 128));
        ALGOS.put("sha512/224", new Algo("SHA-512/224", 128));
        ALGOS.put("sha512/256", new Algo("SHA-512/256", 128));
        ALGOS.put("sha512", new Algo("SHA-512", 128));
        ALGOS.put("sha3-224", new Algo("SHA3-224", 144));
        ALGOS.put("sha3-256", new Algo("SHA3-256", 136));
        ALGOS.put("sha3-384", new Algo("SHA3-384", 104));
        ALGOS.put("sha3-512", new Algo("SHA3-512", 72));
        // Non-cryptographic checksums: hash() only, not hash_hmac().
        ALGOS.put("crc32b", new Algo(null, 0));
        ALGOS.put("crc32c", new Algo(null, 0));
    }

    private HashFunctions() {
    }

    static void register() {
        Builtins.def("hash", 2, 4, (ctx, a) -> {
            String algo = PhpStrings.lower(PhpOps.toStr(a[0]));
            byte[] digest = digest(algo, PhpStrings.bytes(PhpOps.toStr(a[1])), "hash");
            return output(digest, a.length > 2 && PhpOps.toBool(a[2]));
        });
        Builtins.def("hash_hmac", 3, 4, (ctx, a) -> {
            String algo = PhpStrings.lower(PhpOps.toStr(a[0]));
            Algo spec = ALGOS.get(algo);
            if (spec == null || spec.javaName() == null) {
                throw new PhpError(PhpError.VALUE_ERROR, "hash_hmac(): Argument #1 ($algo) must be a valid cryptographic hashing algorithm", null);
            }
            byte[] mac = hmac(algo, spec, PhpStrings.bytes(PhpOps.toStr(a[2])), PhpStrings.bytes(PhpOps.toStr(a[1])));
            return output(mac, a.length > 3 && PhpOps.toBool(a[3]));
        });
        Builtins.def("hash_file", 2, 4, (ctx, a) -> {
            byte[] data = StreamFunctions.readFile(ctx, PhpOps.toStr(a[1]), "hash_file");
            return data == null ? false : output(digest(PhpStrings.lower(PhpOps.toStr(a[0])), data, "hash_file"),
                    a.length > 2 && PhpOps.toBool(a[2]));
        });
        Builtins.def("hash_hmac_file", 3, 4, (ctx, a) -> {
            byte[] data = StreamFunctions.readFile(ctx, PhpOps.toStr(a[1]), "hash_hmac_file");
            if (data == null) {
                return false;
            }
            String algo = PhpStrings.lower(PhpOps.toStr(a[0]));
            Algo spec = crypto(algo, "hash_hmac_file");
            return output(hmac(algo, spec, PhpStrings.bytes(PhpOps.toStr(a[2])), data), a.length > 3 && PhpOps.toBool(a[3]));
        });
        incremental();
        Builtins.def("hash_equals", 2, 2, (ctx, a) -> {
            if (!(a[0] instanceof String known)) {
                throw new PhpError(PhpError.TYPE_ERROR, "hash_equals(): Argument #1 ($known_string) must be of type string, " + PhpOps.typeName(a[0]) + " given", null);
            }
            if (!(a[1] instanceof String user)) {
                throw new PhpError(PhpError.TYPE_ERROR, "hash_equals(): Argument #2 ($user_string) must be of type string, " + PhpOps.typeName(a[1]) + " given", null);
            }
            return MessageDigest.isEqual(PhpStrings.bytes(known), PhpStrings.bytes(user));
        });
        Builtins.def("hash_algos", 0, 0, (ctx, a) -> PhpArray.ofList(ALGOS.keySet().stream().toList()));
        Builtins.def("hash_hmac_algos", 0, 0, (ctx, a) ->
                PhpArray.ofList(ALGOS.entrySet().stream().filter(e -> e.getValue().javaName() != null).map(Map.Entry::getKey).toList()));
        Builtins.def("hex2bin", 1, 1, (ctx, a) -> {
            String hex = PhpOps.toStr(a[0]);
            if (hex.length() % 2 != 0) {
                ctx.warning("hex2bin(): Hexadecimal input string must have an even length", 0);
                return false;
            }
            try {
                return PhpStrings.of(HexFormat.of().parseHex(hex));
            } catch (IllegalArgumentException e) {
                ctx.warning("hex2bin(): Input string must be hexadecimal string", 0);
                return false;
            }
        });
        Builtins.def("sodium_crypto_generichash", 1, 3, (ctx, a) -> {
            byte[] msg = PhpStrings.bytes(PhpOps.toStr(a[0]));
            byte[] key = a.length > 1 && a[1] != dev.phpruntime.runtime.PhpNull.INSTANCE
                    ? PhpStrings.bytes(PhpOps.toStr(a[1])) : new byte[0];
            long len = a.length > 2 ? PhpOps.toLong(a[2]) : 32L;
            if (key.length != 0 && (key.length < 16 || key.length > 64)) {
                throw new PhpError("SodiumException", "sodium_crypto_generichash(): Argument #2 ($key) must be at least 16 bytes long and at most 64 bytes long", null);
            }
            if (len < 16 || len > 64) {
                throw new PhpError("SodiumException", "sodium_crypto_generichash(): Argument #3 ($length) must be at least 16 and at most 64", null);
            }
            return PhpStrings.of(Blake2b.hash(msg, key, (int) len));
        });
        Builtins.def("random_bytes", 1, 1, (ctx, a) -> {
            long length = PhpOps.toLong(a[0]);
            if (length < 1 || length > Integer.MAX_VALUE - 8) {
                throw new PhpError(PhpError.VALUE_ERROR, "random_bytes(): Argument #1 ($length) must be greater than 0", null);
            }
            byte[] raw = new byte[(int) length];
            RANDOM.nextBytes(raw);
            return PhpStrings.of(raw);
        });
    }

    static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private static Algo crypto(String algo, String fn) {
        Algo spec = ALGOS.get(algo);
        if (spec == null || spec.javaName() == null) {
            throw new PhpError(PhpError.VALUE_ERROR, fn + "(): Argument #1 ($algo) must be a valid cryptographic hashing algorithm", null);
        }
        return spec;
    }

    /** Request-owned HashContext handle; never stored in a shared AST node. */
    static final class HashContext {
        final String algo;
        final byte[] key;
        final java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();

        HashContext(String algo, byte[] key) {
            this.algo = algo;
            this.key = key;
        }
    }

    private static HashContext hashContext(dev.phpruntime.PhpContext ctx, Object value, String fn) {
        if (value instanceof PhpObject object && object.phpClass().template() == ctx.language().builtinClassTemplate("hashcontext")) {
            HashContext hash = ContextState.of(ctx).hashContexts.get(object.id());
            if (hash != null) {
                return hash;
            }
            throw new PhpError(PhpError.VALUE_ERROR, fn + "(): Argument #1 ($context) must be a valid, non-finalized HashContext", null);
        }
        throw new PhpError(PhpError.TYPE_ERROR, fn + "(): Argument #1 ($context) must be a valid HashContext", null);
    }

    private static PhpObject hashObject(dev.phpruntime.PhpContext ctx, HashContext hash) {
        PhpObject object = new PhpObject(ctx.lookupClass("HashContext"), ctx.nextObjectId());
        ContextState.of(ctx).hashContexts.put(object.id(), hash);
        return object;
    }

    private static void incremental() {
        Builtins.def("hash_init", 1, 4, (ctx, a) -> {
            String algo = PhpStrings.lower(PhpOps.toStr(a[0]));
            if (!ALGOS.containsKey(algo)) {
                digest(algo, new byte[0], "hash_init");
            }
            boolean hmac = a.length > 1 && (PhpOps.toLong(a[1]) & 1) != 0;
            byte[] key = hmac ? a.length > 2 ? PhpStrings.bytes(PhpOps.toStr(a[2])) : new byte[0] : null;
            if (hmac) {
                crypto(algo, "hash_init");
                if (key.length == 0) {
                    throw new PhpError(PhpError.VALUE_ERROR, "hash_init(): Argument #3 ($key) must not be empty when HMAC is requested", null);
                }
            }
            return hashObject(ctx, new HashContext(algo, key));
        });
        Builtins.def("hash_update", 2, 2, (ctx, a) -> {
            hashContext(ctx, a[0], "hash_update").data.writeBytes(PhpStrings.bytes(PhpOps.toStr(a[1])));
            return true;
        });
        Builtins.def("hash_update_file", 2, 3, (ctx, a) -> {
            HashContext hash = hashContext(ctx, a[0], "hash_update_file");
            byte[] data = StreamFunctions.readFile(ctx, PhpOps.toStr(a[1]), "hash_update_file");
            if (data == null) {
                return false;
            }
            hash.data.writeBytes(data);
            return true;
        });
        Builtins.def("hash_update_stream", 2, 3, (ctx, a) -> {
            HashContext hash = hashContext(ctx, a[0], "hash_update_stream");
            PhpStream stream = StreamFunctions.stream(StreamFunctions.resource(a[1], "hash_update_stream"), "hash_update_stream");
            try {
                long length = a.length > 2 ? PhpOps.toLong(a[2]) : -1;
                byte[] data = length < 0 ? stream.readAll() : stream.readBytes((int) length);
                hash.data.writeBytes(data);
                return (long) data.length;
            } catch (java.io.IOException e) {
                return 0L;
            }
        });
        Builtins.def("hash_copy", 1, 1, (ctx, a) -> {
            HashContext hash = hashContext(ctx, a[0], "hash_copy");
            HashContext copy = new HashContext(hash.algo, hash.key);
            copy.data.writeBytes(hash.data.toByteArray());
            return hashObject(ctx, copy);
        });
        Builtins.def("hash_final", 1, 2, (ctx, a) -> {
            HashContext hash = hashContext(ctx, a[0], "hash_final");
            byte[] data = hash.data.toByteArray();
            byte[] digest = hash.key == null ? digest(hash.algo, data, "hash_final")
                    : hmac(hash.algo, crypto(hash.algo, "hash_final"), hash.key, data);
            ContextState.of(ctx).hashContexts.remove(((PhpObject) a[0]).id());
            return output(digest, a.length > 1 && PhpOps.toBool(a[1]));
        });
        Builtins.def("hash_pbkdf2", 4, 6, (ctx, a) -> {
            String algo = PhpStrings.lower(PhpOps.toStr(a[0]));
            Algo spec = crypto(algo, "hash_pbkdf2");
            byte[] password = PhpStrings.bytes(PhpOps.toStr(a[1]));
            byte[] salt = PhpStrings.bytes(PhpOps.toStr(a[2]));
            long iterations = PhpOps.toLong(a[3]);
            if (iterations < 1) {
                throw new PhpError(PhpError.VALUE_ERROR, "hash_pbkdf2(): Argument #4 ($iterations) must be greater than 0", null);
            }
            boolean raw = a.length > 5 && PhpOps.toBool(a[5]);
            int digestLength = digest(algo, new byte[0], "hash_pbkdf2").length;
            int length = a.length > 4 ? (int) PhpOps.toLong(a[4]) : 0;
            int chars = length == 0 ? digestLength * (raw ? 1 : 2) : length;
            if (chars < 0) {
                throw new PhpError(PhpError.VALUE_ERROR, "hash_pbkdf2(): Argument #5 ($length) must be greater than or equal to 0", null);
            }
            int bytes = raw ? chars : (chars + 1) / 2;
            byte[] result = new byte[bytes];
            for (int block = 1, offset = 0; offset < bytes; block++) {
                byte[] input = java.util.Arrays.copyOf(salt, salt.length + 4);
                java.nio.ByteBuffer.wrap(input, salt.length, 4).putInt(block);
                byte[] u = hmac(algo, spec, password, input);
                byte[] combined = u.clone();
                for (long i = 1; i < iterations; i++) {
                    u = hmac(algo, spec, password, u);
                    for (int j = 0; j < combined.length; j++) {
                        combined[j] ^= u[j];
                    }
                }
                int n = Math.min(combined.length, bytes - offset);
                System.arraycopy(combined, 0, result, offset, n);
                offset += n;
            }
            return raw ? PhpStrings.of(result) : HexFormat.of().formatHex(result).substring(0, chars);
        });
        Builtins.def("hash_hkdf", 2, 5, (ctx, a) -> {
            String algo = PhpStrings.lower(PhpOps.toStr(a[0]));
            Algo spec = crypto(algo, "hash_hkdf");
            byte[] key = PhpStrings.bytes(PhpOps.toStr(a[1]));
            int digestLength = digest(algo, new byte[0], "hash_hkdf").length;
            int length = a.length > 2 ? (int) PhpOps.toLong(a[2]) : 0;
            length = length == 0 ? digestLength : length;
            if (key.length == 0 || length < 0 || length > 255 * digestLength) {
                throw new PhpError(PhpError.VALUE_ERROR, "hash_hkdf(): Invalid key or output length", null);
            }
            byte[] info = a.length > 3 ? PhpStrings.bytes(PhpOps.toStr(a[3])) : new byte[0];
            byte[] salt = a.length > 4 ? PhpStrings.bytes(PhpOps.toStr(a[4])) : new byte[digestLength];
            byte[] prk = hmac(algo, spec, salt, key);
            byte[] previous = new byte[0];
            byte[] result = new byte[length];
            for (int block = 1, offset = 0; offset < length; block++) {
                byte[] input = java.util.Arrays.copyOf(previous, previous.length + info.length + 1);
                System.arraycopy(info, 0, input, previous.length, info.length);
                input[input.length - 1] = (byte) block;
                previous = hmac(algo, spec, prk, input);
                int n = Math.min(previous.length, length - offset);
                System.arraycopy(previous, 0, result, offset, n);
                offset += n;
            }
            return PhpStrings.of(result);
        });
    }

    private static Object output(byte[] digest, boolean binary) {
        return binary ? PhpStrings.of(digest) : HexFormat.of().formatHex(digest);
    }

    static byte[] digest(String algo, byte[] data, String fn) {
        Algo spec = ALGOS.get(algo);
        if (spec == null) {
            throw new PhpError(PhpError.VALUE_ERROR, fn + "(): Argument #1 ($algo) must be a valid hashing algorithm", null);
        }
        if (spec.javaName() == null) {
            Checksum c = algo.equals("crc32b") ? new CRC32() : new CRC32C();
            c.update(data, 0, data.length);
            long v = c.getValue();
            return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
        }
        try {
            return MessageDigest.getInstance(spec.javaName()).digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new PhpError(PhpError.VALUE_ERROR, fn + "(): Argument #1 ($algo) must be a valid hashing algorithm", null);
        }
    }

    /** RFC 2104 HMAC over any of the digests above. */
    private static byte[] hmac(String algo, Algo spec, byte[] key, byte[] data) {
        int block = spec.blockSize();
        if (key.length > block) {
            key = digest(algo, key, "hash_hmac");
        }
        byte[] k = new byte[block];
        System.arraycopy(key, 0, k, 0, key.length);
        byte[] ipad = new byte[block + data.length];
        byte[] opadKey = new byte[block];
        for (int i = 0; i < block; i++) {
            ipad[i] = (byte) (k[i] ^ 0x36);
            opadKey[i] = (byte) (k[i] ^ 0x5c);
        }
        System.arraycopy(data, 0, ipad, block, data.length);
        byte[] inner = digest(algo, ipad, "hash_hmac");
        byte[] outer = new byte[block + inner.length];
        System.arraycopy(opadKey, 0, outer, 0, block);
        System.arraycopy(inner, 0, outer, block, inner.length);
        return digest(algo, outer, "hash_hmac");
    }
}
