package dev.phpruntime.builtins;

import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpStrings;

import java.io.ByteArrayOutputStream;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** gzdeflate, gzcompress, gzencode, their inverses and zlib_encode/zlib_decode, via java.util.zip. */
final class ZlibFunctions {

    static final int RAW = -15;
    static final int GZIP = 31;
    static final int DEFLATE = 15;

    private ZlibFunctions() {
    }

    static void register() {
        Builtins.def("gzdeflate", 1, 3, (ctx, a) -> encode(a, RAW, "gzdeflate"));
        Builtins.def("gzcompress", 1, 3, (ctx, a) -> encode(a, DEFLATE, "gzcompress"));
        Builtins.def("gzencode", 1, 3, (ctx, a) -> encode(a, GZIP, "gzencode"));
        Builtins.def("zlib_encode", 2, 3, (ctx, a) -> {
            int encoding = (int) PhpOps.toLong(a[1]);
            Object[] args = {a[0], a.length > 2 ? a[2] : -1L};
            return encode(args, encoding, "zlib_encode");
        });
        Builtins.def("gzinflate", 1, 2, (ctx, a) -> decode(ctx, a, RAW, "gzinflate"));
        Builtins.def("gzuncompress", 1, 2, (ctx, a) -> decode(ctx, a, DEFLATE, "gzuncompress"));
        Builtins.def("gzdecode", 1, 2, (ctx, a) -> decode(ctx, a, GZIP, "gzdecode"));
        Builtins.def("zlib_decode", 1, 2, (ctx, a) -> decode(ctx, a, 0, "zlib_decode"));
    }

    private static Object encode(Object[] a, int encoding, String fn) {
        byte[] data = PhpStrings.bytes(PhpOps.toStr(a[0]));
        int level = a.length > 1 ? (int) PhpOps.toLong(a[1]) : -1;
        if (level < -1 || level > 9) {
            throw new PhpError("ValueError", fn + "(): Argument #2 ($level) must be between -1 and 9", null);
        }
        if (a.length > 2 && !fn.equals("zlib_encode")) {
            encoding = (int) PhpOps.toLong(a[2]);
        }
        if (encoding != RAW && encoding != GZIP && encoding != DEFLATE) {
            throw new PhpError("ValueError", fn + "(): Argument #" + (fn.equals("zlib_encode") ? 2 : 3)
                    + " ($encoding) must be one of ZLIB_ENCODING_RAW, ZLIB_ENCODING_GZIP, or ZLIB_ENCODING_DEFLATE", null);
        }
        Deflater d = new Deflater(level, encoding != DEFLATE);
        try {
            d.setInput(data);
            d.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (encoding == GZIP) {
                int effective = level == -1 ? 6 : level;
                int xfl = effective == 9 ? 2 : (effective < 2 ? 4 : 0);
                out.writeBytes(new byte[]{0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0, (byte) xfl, 3});
            }
            byte[] buf = new byte[8192];
            while (!d.finished()) {
                int n = d.deflate(buf);
                out.write(buf, 0, n);
            }
            if (encoding == GZIP) {
                CRC32 crc = new CRC32();
                crc.update(data);
                writeLe(out, (int) crc.getValue());
                writeLe(out, data.length);
            }
            return PhpStrings.of(out.toByteArray());
        } finally {
            d.end();
        }
    }

    private static Object decode(dev.phpruntime.PhpContext ctx, Object[] a, int encoding, String fn) {
        byte[] data = PhpStrings.bytes(PhpOps.toStr(a[0]));
        long max = a.length > 1 ? PhpOps.toLong(a[1]) : 0;
        if (encoding == 0) {
            encoding = data.length >= 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b ? GZIP
                    : (data.length >= 2 && ((data[0] & 0xff) * 256 + (data[1] & 0xff)) % 31 == 0 && (data[0] & 0x0f) == 8 ? DEFLATE : RAW);
        }
        int offset = 0;
        if (encoding == GZIP) {
            offset = gzipHeaderLength(data);
            if (offset < 0) {
                ctx.warning(fn + "(): data error", 0);
                return false;
            }
        }
        Inflater inf = new Inflater(encoding != DEFLATE);
        try {
            inf.setInput(data, offset, data.length - offset);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) {
                    ctx.warning(fn + "(): data error", 0);
                    return false;
                }
                out.write(buf, 0, n);
                if (max > 0 && out.size() > max) {
                    ctx.warning(fn + "(): insufficient memory", 0);
                    return false;
                }
            }
            return PhpStrings.of(out.toByteArray());
        } catch (DataFormatException e) {
            ctx.warning(fn + "(): data error", 0);
            return false;
        } finally {
            inf.end();
        }
    }

    /** Length of a gzip member header (RFC 1952), or -1 if it is not one. */
    private static int gzipHeaderLength(byte[] d) {
        if (d.length < 10 || (d[0] & 0xff) != 0x1f || (d[1] & 0xff) != 0x8b || d[2] != 8) {
            return -1;
        }
        int flags = d[3] & 0xff;
        int at = 10;
        if ((flags & 4) != 0) {
            if (at + 2 > d.length) {
                return -1;
            }
            at += 2 + ((d[at] & 0xff) | (d[at + 1] & 0xff) << 8);
        }
        if ((flags & 8) != 0) {
            while (at < d.length && d[at] != 0) {
                at++;
            }
            at++;
        }
        if ((flags & 16) != 0) {
            while (at < d.length && d[at] != 0) {
                at++;
            }
            at++;
        }
        if ((flags & 2) != 0) {
            at += 2;
        }
        return at <= d.length ? at : -1;
    }

    private static void writeLe(ByteArrayOutputStream out, int v) {
        out.write(v);
        out.write(v >>> 8);
        out.write(v >>> 16);
        out.write(v >>> 24);
    }
}
