package dev.phpruntime.builtins;

import com.oracle.truffle.api.TruffleFile;
import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpResource;
import dev.phpruntime.runtime.PhpStrings;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** gzopen and the gz* stream functions, backed by java.util.zip. */
final class GzipFunctions {

    private GzipFunctions() {
    }

    static void register() {
        Builtins.def("gzopen", 2, 3, (ctx, a) -> {
            String path = PhpOps.toStr(a[0]);
            String mode = PhpOps.toStr(a[1]);
            GzipStream stream = GzipStream.open(ctx, path, mode);
            if (stream == null) {
                ctx.warning("gzopen(" + path + "): Failed to open stream: No such file or directory", 0);
                return false;
            }
            return new PhpResource(StreamFunctions.streamId(ctx), "stream", stream);
        });
        Builtins.def("gzclose", 1, 1, (ctx, a) -> {
            PhpResource r = StreamFunctions.resource(a[0], "gzclose");
            PhpStream s = StreamFunctions.stream(r, "gzclose");
            try {
                s.close();
            } catch (IOException ignored) {
                // best effort, as with fclose
            }
            r.close();
            return true;
        });
        Builtins.def("gzread", 2, 2, (ctx, a) -> {
            GzipStream s = gzipStream(a[0], "gzread");
            long length = PhpOps.toLong(a[1]);
            if (length <= 0) {
                throw new PhpError(PhpError.VALUE_ERROR, "gzread(): Argument #2 ($length) must be greater than 0", null);
            }
            try {
                return PhpStrings.of(s.readAllOr(length < 0 ? Integer.MAX_VALUE : (int) length));
            } catch (IOException e) {
                return false;
            }
        });
        Builtins.def("gzwrite", 2, 3, GzipFunctions::gzwrite);
        Builtins.def("gzputs", 2, 3, GzipFunctions::gzwrite);
        Builtins.def("gzeof", 1, 1, (ctx, a) -> gzipStream(a[0], "gzeof").eof);
        Builtins.def("gzgets", 1, 2, (ctx, a) -> {
            GzipStream s = gzipStream(a[0], "gzgets");
            int max = a.length > 1 && a[1] != dev.phpruntime.runtime.PhpNull.INSTANCE ? (int) PhpOps.toLong(a[1]) : 1024;
            try {
                byte[] line = s.readLine(max);
                if (line.length == 0 && s.eof) {
                    return false;
                }
                return PhpStrings.of(line);
            } catch (IOException e) {
                return false;
            }
        });
        Builtins.def("gztell", 1, 1, (ctx, a) -> gzipStream(a[0], "gztell").tell());
        Builtins.def("gzrewind", 1, 1, (ctx, a) -> {
            GzipStream s = gzipStream(a[0], "gzrewind");
            return s.seek(0);
        });
        Builtins.def("gzseek", 2, 3, (ctx, a) -> {
            GzipStream s = gzipStream(a[0], "gzseek");
            long offset = PhpOps.toLong(a[1]);
            long whence = a.length > 2 ? PhpOps.toLong(a[2]) : 0L;
            if (whence == 2) {
                ctx.warning("gzseek(): SEEK_END is not supported", 0);
                return -1L;
            }
            long base = whence == 1 ? s.tell() : 0L;
            long target = base + offset;
            // php's gzseek reports 0 on success, not the new offset.
            return s.seek(target) ? 0L : -1L;
        });
    }

    private static Object gzwrite(PhpContext ctx, Object[] a) {
        GzipStream s = gzipStream(a[0], "gzwrite");
        byte[] data = PhpStrings.bytes(PhpOps.toStr(a[1]));
        int length = data.length;
        if (a.length > 2 && a[2] != dev.phpruntime.runtime.PhpNull.INSTANCE) {
            length = Math.min(length, (int) PhpOps.toLong(a[2]));
        }
        try {
            s.write(length == data.length ? data : java.util.Arrays.copyOf(data, length));
            return (long) length;
        } catch (IOException e) {
            return false;
        }
    }

    private static GzipStream gzipStream(Object value, String fn) {
        PhpStream stream = StreamFunctions.stream(value, fn);
        if (!(stream instanceof GzipStream gz)) {
            throw new PhpError(fn + "(): Argument #1 ($stream) must be a gzopen() stream resource", null);
        }
        return gz;
    }

    /** A gzip stream held in memory: reads decompress once, writes compress on close/flush. */
    static final class GzipStream extends PhpStream {

        private byte[] content;
        private int position;
        private final TruffleFile file;
        private final int level;
        private final boolean readOnly;
        private final byte[] prefixCompressed;
        private final int writtenStart;
        private boolean written;

        private GzipStream(String mode, String uri, TruffleFile file, int level, byte[] content,
                           boolean readOnly, byte[] prefixCompressed, int writtenStart) {
            super(mode, uri);
            this.file = file;
            this.level = level;
            this.content = content;
            this.readOnly = readOnly;
            this.prefixCompressed = prefixCompressed;
            this.writtenStart = writtenStart;
        }

        static GzipStream open(PhpContext ctx, String path, String mode) {
            if (path.isEmpty() || mode.isEmpty()) {
                return null;
            }
            char first = mode.charAt(0);
            if (first != 'r' && first != 'w' && first != 'a' && first != 'x') {
                return null;
            }
            int level = -1;
            for (int i = 1; i < mode.length(); i++) {
                char c = mode.charAt(i);
                if (c >= '0' && c <= '9') {
                    level = c - '0';
                } else if (c != 'b' && c != 't') {
                    return null;
                }
            }
            if (first == 'r') {
                level = -1;
            } else if (level < 0) {
                level = 6;
            }
            try {
                TruffleFile file = ctx.env().getPublicTruffleFile(PhpStrings.toJava(path));
                byte[] content = new byte[0];
                byte[] prefix = new byte[0];
                boolean readOnly = first == 'r';
                if (first == 'r') {
                    if (!file.exists() || file.isDirectory()) {
                        return null;
                    }
                    content = decompress(file.readAllBytes());
                } else if (first == 'a') {
                    if (file.exists()) {
                        prefix = file.readAllBytes();
                        content = decompress(prefix);
                    }
                } else if (first == 'x') {
                    if (file.exists()) {
                        return null;
                    }
                }
                GzipStream stream = new GzipStream(mode, PhpStrings.fromJava(file.getPath()), file, level,
                        content, readOnly, prefix, content.length);
                stream.position = first == 'a' ? content.length : 0;
                return stream;
            } catch (IOException | SecurityException | IllegalArgumentException e) {
                return null;
            }
        }

        private static byte[] decompress(byte[] data) throws IOException {
            if (data.length >= 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b) {
                try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
                    return in.readAllBytes();
                }
            }
            return data;
        }

        @Override
        int read(byte[] buf, int off, int len) {
            if (position >= content.length) {
                eof = true;
                return -1;
            }
            int n = Math.min(len, content.length - position);
            System.arraycopy(content, position, buf, off, n);
            position += n;
            return n;
        }

        @Override
        void write(byte[] data) throws IOException {
            if (readOnly) {
                throw new IOException("read-only gzip stream");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + data.length);
            out.write(content);
            out.write(data);
            content = out.toByteArray();
            position = content.length;
            written = true;
        }

        /** gzread's variant: reads up to n bytes and clears eof when data was available. */
        byte[] readAllOr(int n) throws IOException {
            byte[] out = readBytes(n);
            return out;
        }

        long sizeOrZero() {
            return content.length;
        }

        @Override
        long tell() {
            return position;
        }

        @Override
        boolean seek(long pos) {
            if (pos < 0 || pos > content.length) {
                return false;
            }
            position = (int) pos;
            eof = false;
            return true;
        }

        @Override
        long size() {
            return content.length;
        }

        @Override
        boolean seekable() {
            return true;
        }

        @Override
        void flush() throws IOException {
            if (!readOnly && written) {
                writeFile();
            }
        }

        @Override
        void close() throws IOException {
            if (!readOnly) {
                writeFile();
            }
        }

        private void writeFile() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 32);
            if (prefixCompressed.length > 0) {
                out.write(prefixCompressed);
            }
            byte[] tail = prefixCompressed.length > 0
                    ? java.util.Arrays.copyOfRange(content, writtenStart, content.length)
                    : content;
            if (tail.length > 0 || prefixCompressed.length == 0) {
                try (GZIPOutputStream gz = new GZIPOutputStream(out) {
                    {
                        if (level >= 0) {
                            def.setLevel(level);
                        }
                    }
                }) {
                    gz.write(tail);
                }
            }
            try (SeekableByteChannel ch = file.newByteChannel(Set.of(StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))) {
                ch.write(ByteBuffer.wrap(out.toByteArray()));
            }
            written = false;
        }

        @Override
        String wrapperType() {
            return "zlib";
        }

        @Override
        String streamType() {
            return "ZLIB";
        }
    }
}
