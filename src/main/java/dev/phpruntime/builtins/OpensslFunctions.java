package dev.phpruntime.builtins;

import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpStrings;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/** openssl_encrypt, openssl_decrypt and the cipher metadata helpers Tempest's encrypter uses. */
final class OpensslFunctions {
    private static final int RAW_DATA = 1;
    private static final int ZERO_PADDING = 2;
    private static final int DONT_ZERO_PAD_KEY = 4;

    private OpensslFunctions() {
    }

    private record CipherSpec(String javaName, int keyLength, int ivLength, boolean aead, boolean chacha) {
    }

    private static CipherSpec spec(String name) {
        return switch (PhpStrings.lower(name)) {
            case "aes-128-cbc" -> new CipherSpec("AES/CBC/PKCS5Padding", 16, 16, false, false);
            case "aes-192-cbc" -> new CipherSpec("AES/CBC/PKCS5Padding", 24, 16, false, false);
            case "aes-256-cbc" -> new CipherSpec("AES/CBC/PKCS5Padding", 32, 16, false, false);
            case "aes-128-gcm" -> new CipherSpec("AES/GCM/NoPadding", 16, 12, true, false);
            case "aes-192-gcm" -> new CipherSpec("AES/GCM/NoPadding", 24, 12, true, false);
            case "aes-256-gcm" -> new CipherSpec("AES/GCM/NoPadding", 32, 12, true, false);
            case "chacha20-poly1305" -> new CipherSpec("ChaCha20-Poly1305", 32, 12, true, true);
            case "aes-128-ecb" -> new CipherSpec("AES/ECB/PKCS5Padding", 16, 0, false, false);
            case "aes-192-ecb" -> new CipherSpec("AES/ECB/PKCS5Padding", 24, 0, false, false);
            case "aes-256-ecb" -> new CipherSpec("AES/ECB/PKCS5Padding", 32, 0, false, false);
            default -> null;
        };
    }

    static void register() {
        Builtins.def("openssl_cipher_iv_length", 1, 1, (ctx, a) -> {
            CipherSpec spec = spec(PhpOps.toStr(a[0]));
            if (spec == null) {
                ctx.warning("openssl_cipher_iv_length(): Unknown cipher algorithm", 0);
                return false;
            }
            return (long) spec.ivLength();
        });
        Builtins.def("openssl_cipher_key_length", 1, 1, (ctx, a) -> {
            CipherSpec spec = spec(PhpOps.toStr(a[0]));
            if (spec == null) {
                ctx.warning("openssl_cipher_key_length(): Unknown cipher algorithm", 0);
                return false;
            }
            return (long) spec.keyLength();
        });
        Builtins.defRef("openssl_encrypt", 3, 8, 1 << 5, OpensslFunctions::encrypt);
        Builtins.def("openssl_decrypt", 3, 7, OpensslFunctions::decrypt);
        Builtins.def("openssl_error_string", 0, 0, (ctx, a) -> {
            ContextState state = ContextState.of(ctx);
            String error = state.opensslError;
            state.opensslError = null;
            return error == null ? false : error;
        });
    }

    private static Object encrypt(dev.phpruntime.PhpContext ctx, Object[] a) {
        CipherSpec spec = spec(PhpOps.toStr(a[1]));
        if (spec == null) {
            ctx.warning("openssl_encrypt(): Unknown cipher algorithm", 0);
            return false;
        }
        byte[] data = PhpStrings.bytes(PhpOps.toStr(a[0]));
        byte[] key = key(PhpOps.toStr(a[2]), spec);
        int options = a.length > 3 ? (int) PhpOps.toLong(a[3]) : 0;
        byte[] iv = a.length > 4 ? PhpStrings.bytes(PhpOps.toStr(a[4])) : new byte[0];
        byte[] aad = a.length > 6 ? PhpStrings.bytes(PhpOps.toStr(a[6])) : new byte[0];
        int tagLength = a.length > 7 ? (int) PhpOps.toLong(a[7]) : 16;
        try {
            Cipher cipher = Cipher.getInstance(spec.javaName());
            if (spec.chacha()) {
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"),
                        new IvParameterSpec(iv));
            } else if (spec.aead()) {
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(tagLength * 8, iv));
            } else if (spec.ivLength() == 0) {
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            } else {
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            }
            if (aad.length > 0) {
                cipher.updateAAD(aad);
            }
            byte[] out = cipher.doFinal(data);
            if (spec.aead() && a.length > 5) {
                // doFinal appends the tag; php returns it through the by-reference $tag.
                byte[] tag = new byte[tagLength];
                System.arraycopy(out, out.length - tagLength, tag, 0, tagLength);
                byte[] payload = java.util.Arrays.copyOf(out, out.length - tagLength);
                a[5] = PhpStrings.of(tag);
                out = payload;
            }
            return (options & RAW_DATA) != 0 ? PhpStrings.of(out)
                    : Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            ContextState.of(ctx).opensslError = "error:0607A082:digital envelope routines:EVP_EncryptFinal_ex:"
                    + e.getClass().getSimpleName().toLowerCase();
            return false;
        }
    }

    private static Object decrypt(dev.phpruntime.PhpContext ctx, Object[] a) {
        CipherSpec spec = spec(PhpOps.toStr(a[1]));
        if (spec == null) {
            ctx.warning("openssl_decrypt(): Unknown cipher algorithm", 0);
            return false;
        }
        String input = PhpOps.toStr(a[0]);
        int options = a.length > 3 ? (int) PhpOps.toLong(a[3]) : 0;
        byte[] data;
        if ((options & RAW_DATA) != 0) {
            data = PhpStrings.bytes(input);
        } else {
            try {
                data = Base64.getDecoder().decode(input.getBytes(StandardCharsets.ISO_8859_1));
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        byte[] key = key(PhpOps.toStr(a[2]), spec);
        byte[] iv = a.length > 4 ? PhpStrings.bytes(PhpOps.toStr(a[4])) : new byte[0];
        byte[] aad = a.length > 6 ? PhpStrings.bytes(PhpOps.toStr(a[6])) : new byte[0];
        byte[] tag = a.length > 5 && a[5] != dev.phpruntime.runtime.PhpNull.INSTANCE
                ? PhpStrings.bytes(PhpOps.toStr(a[5])) : new byte[0];
        try {
            Cipher cipher = Cipher.getInstance(spec.javaName());
            if (spec.chacha()) {
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "ChaCha20"),
                        new IvParameterSpec(iv));
            } else if (spec.aead()) {
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(Math.max(tag.length, 16) * 8, iv));
            } else if (spec.ivLength() == 0) {
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            } else {
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            }
            if (aad.length > 0) {
                cipher.updateAAD(aad);
            }
            byte[] out = spec.aead()
                    ? cipher.doFinal(concat(data, tag))
                    : cipher.doFinal(data);
            return PhpStrings.of(out);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            ContextState.of(ctx).opensslError = "error:1C800064:Provider routines:bad decrypt";
            return false;
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** OpenSSL uses the passphrase as the key, null-padding or truncating to the key length. */
    private static byte[] key(String passphrase, CipherSpec spec) {
        byte[] raw = PhpStrings.bytes(passphrase);
        byte[] key = new byte[spec.keyLength()];
        System.arraycopy(raw, 0, key, 0, Math.min(raw.length, key.length));
        return key;
    }
}
