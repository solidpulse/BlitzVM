package dev.phpruntime.builtins;

import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpStrings;

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
