package dev.phpruntime.builtins;

import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpStrings;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * crypt() for the modular hash formats: bcrypt ($2y$), MD5 ($1$), SHA-256 ($5$) and
 * SHA-512 ($6$). The traditional DES format is not implemented.
 */
final class CryptFunctions {

    private static final String ITOA64 = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private CryptFunctions() {
    }

    static void register() {
        Builtins.def("crypt", 2, 2, (ctx, a) -> crypt(PhpOps.toStr(a[0]), PhpOps.toStr(a[1])));
    }

    private static String crypt(String password, String salt) {
        if (salt.startsWith("$2y$") || salt.startsWith("$2a$") || salt.startsWith("$2b$")) {
            return bcrypt(password, salt);
        }
        if (salt.startsWith("$1$")) {
            return md5Crypt(password, salt.substring(3));
        }
        if (salt.startsWith("$5$")) {
            return shaCrypt(password, salt.substring(3), false);
        }
        if (salt.startsWith("$6$")) {
            return shaCrypt(password, salt.substring(3), true);
        }
        // Traditional DES and everything else are unsupported. PHP's marker salts map
        // *0 -> *1 and everything malformed to *0.
        return salt.equals("*0") ? "*1" : "*0";
    }

    private static String bcrypt(String password, String salt) {
        if (salt.length() < 29 || salt.charAt(6) != '$') {
            return "*0";
        }
        String costText = salt.substring(4, 6);
        if (!Character.isDigit(costText.charAt(0)) || !Character.isDigit(costText.charAt(1))) {
            return "*0";
        }
        int cost = Integer.parseInt(costText);
        if (cost < 4 || cost > 31) {
            return "*0";
        }
        String body = salt.substring(7);
        if (body.length() < 22) {
            return "*0";
        }
        String salt22 = body.substring(0, 22);
        for (int i = 0; i < 22; i++) {
            if (Bcrypt64.indexOf(salt22.charAt(i)) < 0) {
                return "*0";
            }
        }
        byte[] saltBytes = Bcrypt.decode(salt22, 16);
        String variant = salt.substring(1, 3);
        return Bcrypt.crypt(PhpStrings.bytes(password), cost, saltBytes, variant);
    }

    /** The bcrypt alphabet, same as Bcrypt's private table. */
    private static final String Bcrypt64 = "./ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    // ------------------------------------------------------------------ MD5

    private static String md5Crypt(String password, String saltAndRest) {
        String salt = saltAndRest;
        int dollar = salt.indexOf('$');
        if (dollar >= 0) {
            salt = salt.substring(0, dollar);
        }
        if (salt.length() > 8) {
            salt = salt.substring(0, 8);
        }
        byte[] pw = PhpStrings.bytes(password);
        byte[] sp = PhpStrings.bytes(salt);
        MessageDigest md = md5();
        md.update(pw);
        md.update(PhpStrings.bytes("$1$"));
        md.update(sp);
        MessageDigest alt = md5();
        alt.update(pw);
        alt.update(sp);
        alt.update(pw);
        byte[] altResult = alt.digest();
        for (int pl = pw.length; pl > 0; pl -= 16) {
            md.update(altResult, 0, Math.min(pl, 16));
        }
        for (int i = pw.length; i != 0; i >>= 1) {
            if ((i & 1) != 0) {
                md.update((byte) 0);
            } else {
                md.update(pw, 0, Math.min(1, pw.length));
            }
        }
        byte[] result = md.digest();
        for (int i = 0; i < 1000; i++) {
            MessageDigest ctx = md5();
            if ((i & 1) != 0) {
                ctx.update(pw);
            } else {
                ctx.update(result);
            }
            if (i % 3 != 0) {
                ctx.update(sp);
            }
            if (i % 7 != 0) {
                ctx.update(pw);
            }
            if ((i & 1) != 0) {
                ctx.update(result);
            } else {
                ctx.update(pw);
            }
            result = ctx.digest();
        }
        StringBuilder out = new StringBuilder("$1$").append(salt).append('$');
        long l;
        l = (result[0] & 0xffL) << 16 | (result[6] & 0xffL) << 8 | (result[12] & 0xffL);
        b64(out, l, 4);
        l = (result[1] & 0xffL) << 16 | (result[7] & 0xffL) << 8 | (result[13] & 0xffL);
        b64(out, l, 4);
        l = (result[2] & 0xffL) << 16 | (result[8] & 0xffL) << 8 | (result[14] & 0xffL);
        b64(out, l, 4);
        l = (result[3] & 0xffL) << 16 | (result[9] & 0xffL) << 8 | (result[15] & 0xffL);
        b64(out, l, 4);
        l = (result[4] & 0xffL) << 16 | (result[10] & 0xffL) << 8 | (result[5] & 0xffL);
        b64(out, l, 4);
        l = result[11] & 0xffL;
        b64(out, l, 2);
        return out.toString();
    }

    // --------------------------------------------------------- SHA-256 / SHA-512

    private static String shaCrypt(String password, String saltAndRest, boolean sha512) {
        int rounds = 5000;
        boolean customRounds = false;
        String rest = saltAndRest;
        if (rest.startsWith("rounds=")) {
            int end = rest.indexOf('$', 7);
            if (end < 0) {
                return "*0";
            }
            String number = rest.substring(7, end);
            if (number.isEmpty() || !number.chars().allMatch(Character::isDigit)) {
                return "*0";
            }
            long n;
            try {
                n = Long.parseLong(number);
            } catch (NumberFormatException e) {
                return "*0";
            }
            if (n < 1000L || n > 999_999_999L) {
                return "*0";
            }
            rounds = (int) n;
            customRounds = true;
            rest = rest.substring(end + 1);
        }
        String salt = rest;
        int dollar = salt.indexOf('$');
        if (dollar >= 0) {
            salt = salt.substring(0, dollar);
        }
        if (salt.length() > 16) {
            salt = salt.substring(0, 16);
        }
        byte[] pw = PhpStrings.bytes(password);
        byte[] sp = PhpStrings.bytes(salt);
        int digestSize = sha512 ? 64 : 32;
        String algorithm = sha512 ? "SHA-512" : "SHA-256";

        MessageDigest ctx = digest(algorithm);
        ctx.update(pw);
        ctx.update(sp);
        MessageDigest altCtx = digest(algorithm);
        altCtx.update(pw);
        altCtx.update(sp);
        altCtx.update(pw);
        byte[] altResult = altCtx.digest();
        for (int cnt = pw.length; cnt > digestSize; cnt -= digestSize) {
            ctx.update(altResult);
        }
        ctx.update(altResult, 0, tailLength(pw.length, digestSize));
        for (int cnt = pw.length; cnt > 0; cnt >>= 1) {
            if ((cnt & 1) != 0) {
                ctx.update(altResult);
            } else {
                ctx.update(pw);
            }
        }
        altResult = ctx.digest();

        // p_bytes: hash the password repeated key_len times, then repeat the digest.
        MessageDigest pCtx = digest(algorithm);
        for (int cnt = 0; cnt < pw.length; cnt++) {
            pCtx.update(pw);
        }
        byte[] temp = pCtx.digest();
        byte[] pBytes = repeatDigest(temp, pw.length, digestSize);

        // s_bytes: hash the salt repeated (16 + first byte of the stage-1 digest) times.
        MessageDigest sCtx = digest(algorithm);
        int saltRounds = 16 + (altResult[0] & 0xff);
        for (int cnt = 0; cnt < saltRounds; cnt++) {
            sCtx.update(sp);
        }
        temp = sCtx.digest();
        byte[] sBytes = repeatDigest(temp, sp.length, digestSize);

        for (int cnt = 0; cnt < rounds; cnt++) {
            MessageDigest round = digest(algorithm);
            if ((cnt & 1) != 0) {
                round.update(pBytes);
            } else {
                round.update(altResult);
            }
            if (cnt % 3 != 0) {
                round.update(sBytes);
            }
            if (cnt % 7 != 0) {
                round.update(pBytes);
            }
            if ((cnt & 1) != 0) {
                round.update(altResult);
            } else {
                round.update(pBytes);
            }
            altResult = round.digest();
        }

        StringBuilder out = new StringBuilder(sha512 ? "$6$" : "$5$");
        if (customRounds) {
            out.append("rounds=").append(rounds).append('$');
        }
        out.append(salt).append('$');
        if (sha512) {
            int[][] groups = {
                {0, 21, 42}, {22, 43, 1}, {44, 2, 23}, {3, 24, 45}, {25, 46, 4},
                {47, 5, 26}, {6, 27, 48}, {28, 49, 7}, {50, 8, 29}, {9, 30, 51},
                {31, 52, 10}, {53, 11, 32}, {12, 33, 54}, {34, 55, 13}, {56, 14, 35},
                {15, 36, 57}, {37, 58, 16}, {59, 17, 38}, {18, 39, 60}, {40, 61, 19},
                {62, 20, 41}
            };
            for (int[] g : groups) {
                b64(out, (altResult[g[0]] & 0xffL) << 16 | (altResult[g[1]] & 0xffL) << 8 | (altResult[g[2]] & 0xffL), 4);
            }
            b64(out, altResult[63] & 0xffL, 2);
        } else {
            int[][] groups = {
                {0, 10, 20}, {21, 1, 11}, {12, 22, 2}, {3, 13, 23}, {24, 4, 14},
                {15, 25, 5}, {6, 16, 26}, {27, 7, 17}, {18, 28, 8}, {9, 19, 29}
            };
            for (int[] g : groups) {
                b64(out, (altResult[g[0]] & 0xffL) << 16 | (altResult[g[1]] & 0xffL) << 8 | (altResult[g[2]] & 0xffL), 4);
            }
            b64(out, (altResult[31] & 0xffL) << 8 | (altResult[30] & 0xffL), 3);
        }
        return out.toString();
    }

    /** How many digest bytes the final update in the repeated-hash step consumes. */
    private static int tailLength(int length, int digestSize) {
        int tail = length % digestSize;
        return tail == 0 ? Math.min(length, digestSize) : tail;
    }

    /** Repeats a digest until it fills {@code length} bytes, as glibc's mempcpy loop does. */
    private static byte[] repeatDigest(byte[] digest, int length, int digestSize) {
        byte[] out = new byte[length];
        int at = 0;
        while (length - at >= digestSize) {
            System.arraycopy(digest, 0, out, at, digestSize);
            at += digestSize;
        }
        System.arraycopy(digest, 0, out, at, length - at);
        return out;
    }

    private static void b64(StringBuilder out, long value, int count) {
        for (int i = 0; i < count; i++) {
            out.append(ITOA64.charAt((int) (value & 0x3f)));
            value >>= 6;
        }
    }

    private static MessageDigest md5() {
        return digest("MD5");
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
