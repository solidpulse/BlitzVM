package dev.phpruntime.builtins;

import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpOps;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
            String algo = PhpOps.toStr(a[0]).toLowerCase();
            byte[] digest = digest(algo, bytes(PhpOps.toStr(a[1])), "hash");
            return output(digest, a.length > 2 && PhpOps.toBool(a[2]));
        });
        Builtins.def("hash_hmac", 3, 4, (ctx, a) -> {
            String algo = PhpOps.toStr(a[0]).toLowerCase();
            Algo spec = ALGOS.get(algo);
            if (spec == null || spec.javaName() == null) {
                throw new PhpError("hash_hmac(): Argument #1 ($algo) must be a valid cryptographic hashing algorithm");
            }
            byte[] mac = hmac(algo, spec, bytes(PhpOps.toStr(a[2])), bytes(PhpOps.toStr(a[1])));
            return output(mac, a.length > 3 && PhpOps.toBool(a[3]));
        });
        Builtins.def("hash_equals", 2, 2, (ctx, a) -> {
            if (!(a[0] instanceof String known)) {
                throw new PhpError("hash_equals(): Argument #1 ($known_string) must be of type string, " + PhpOps.typeName(a[0]) + " given");
            }
            if (!(a[1] instanceof String user)) {
                throw new PhpError("hash_equals(): Argument #2 ($user_string) must be of type string, " + PhpOps.typeName(a[1]) + " given");
            }
            return MessageDigest.isEqual(bytes(known), bytes(user));
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
                return fromBytes(HexFormat.of().parseHex(hex));
            } catch (IllegalArgumentException e) {
                ctx.warning("hex2bin(): Input string must be hexadecimal string", 0);
                return false;
            }
        });
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Turns raw bytes into a PHP string. Strings are Java strings until byte strings land, so
     * valid UTF-8 decodes as text and anything else maps each byte to one char (ISO-8859-1).
     */
    static String fromBytes(byte[] b) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, StandardCharsets.ISO_8859_1);
        }
    }

    private static Object output(byte[] digest, boolean binary) {
        return binary ? fromBytes(digest) : HexFormat.of().formatHex(digest);
    }

    static byte[] digest(String algo, byte[] data, String fn) {
        Algo spec = ALGOS.get(algo);
        if (spec == null) {
            throw new PhpError(fn + "(): Argument #1 ($algo) must be a valid hashing algorithm");
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
            throw new PhpError(fn + "(): Argument #1 ($algo) must be a valid hashing algorithm");
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
