package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.Arrays;

/**
 * The handle behind a stream resource. Positions are PHP's logical stream positions: in append
 * mode writes go to the end of the file while the position only counts what was written, as
 * PHP reports it.
 */
abstract class PhpStream {

    final String mode;
    final String uri;
    final boolean canRead;
    final boolean canWrite;
    final boolean append;
    /** Set when a read wanted more bytes and found the end, cleared by seeks (PHP's feof). */
    boolean eof;

    PhpStream(String mode, String uri) {
        this.mode = mode;
        this.uri = uri;
        char m = mode.isEmpty() ? 'r' : mode.charAt(0);
        boolean plus = mode.indexOf('+') >= 0;
        this.canRead = m == 'r' || plus;
        this.canWrite = m != 'r' || plus;
        this.append = m == 'a';
    }

    /** Reads up to len bytes at the current position; -1 at the end. */
    abstract int read(byte[] buf, int off, int len) throws IOException;

    abstract void write(byte[] data) throws IOException;

    abstract long tell();

    /** Moves to an absolute position; false if the stream cannot seek there. */
    abstract boolean seek(long pos) throws IOException;

    abstract long size() throws IOException;

    boolean seekable() {
        return true;
    }

    void truncate(long size) throws IOException {
        throw new IOException("cannot truncate");
    }

    void flush() throws IOException {
    }

    void close() throws IOException {
    }

    abstract String wrapperType();

    abstract String streamType();

    /** Reads up to n bytes, looping over short reads; sets eof if the end stopped it. */
    byte[] readBytes(int n) throws IOException {
        byte[] out = new byte[Math.max(0, n)];
        int got = 0;
        while (got < n) {
            int r = read(out, got, n - got);
            if (r < 0) {
                eof = true;
                break;
            }
            got += r;
        }
        return got == out.length ? out : Arrays.copyOf(out, got);
    }

    /** fgets: up to and including a newline, at most max bytes (or unlimited when max < 0). */
    byte[] readLine(int max) throws IOException {
        java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
        byte[] one = new byte[1];
        while (max < 0 || line.size() < max) {
            int r = read(one, 0, 1);
            if (r < 0) {
                eof = true;
                break;
            }
            line.write(one[0]);
            if (one[0] == '\n') {
                break;
            }
        }
        return line.toByteArray();
    }

    byte[] readAll() throws IOException {
        // A fresh allocation sized from the file, when the stream can tell, avoids the growth
        // copies of a ByteArrayOutputStream for file_get_contents(/stream_get_contents()).
        long remaining;
        try {
            remaining = Math.max(0, size() - tell());
        } catch (IOException e) {
            remaining = 0;
        }
        byte[] out = new byte[(int) Math.min(remaining, 8L << 20)];
        int got = 0;
        byte[] buf = new byte[8192];
        int r;
        while ((r = read(buf, 0, buf.length)) >= 0) {
            if (got + r > out.length) {
                out = Arrays.copyOf(out, Math.max(got + r, Math.max(256, out.length * 2)));
            }
            System.arraycopy(buf, 0, out, got, r);
            got += r;
        }
        eof = true;
        return got == out.length ? out : Arrays.copyOf(out, got);
    }

    // ------------------------------------------------------------------ files

    /** A local file through a TruffleFile channel, read through a small buffer. */
    static final class FileStream extends PhpStream {

        private final SeekableByteChannel channel;
        /** Allocated on the first buffered read: a stream read whole never needs it. */
        private byte[] buffer;
        private long bufferStart;
        private int bufferLength;
        private long position;
        /** Where the channel itself points, so a sequential read does not lseek every buffer. */
        private long channelPosition;

        FileStream(SeekableByteChannel channel, String mode, String uri) {
            super(mode, uri);
            this.channel = channel;
        }

        @Override
        int read(byte[] buf, int off, int len) throws IOException {
            if (position < bufferStart || position >= bufferStart + bufferLength) {
                if (channelPosition != position) {
                    channel.position(position);
                }
                bufferStart = position;
                if (buffer == null) {
                    buffer = new byte[8192];
                }
                int n = channel.read(ByteBuffer.wrap(buffer));
                bufferLength = Math.max(0, n);
                channelPosition = position + bufferLength;
                if (n <= 0) {
                    return -1;
                }
            }
            int at = (int) (position - bufferStart);
            int n = Math.min(len, bufferLength - at);
            System.arraycopy(buffer, at, buf, off, n);
            position += n;
            return n;
        }

        /**
         * The rest of the file read straight into an array sized from the channel, with no
         * intermediate buffer. A file that grew since size() is still read to its end.
         */
        @Override
        byte[] readAll() throws IOException {
            long remaining = Math.max(0, channel.size() - position);
            if (remaining > (8L << 20)) {
                return super.readAll();
            }
            byte[] out = new byte[(int) remaining];
            int got = 0;
            if (position >= bufferStart && position < bufferStart + bufferLength) {
                int at = (int) (position - bufferStart);
                got = Math.min(out.length, bufferLength - at);
                System.arraycopy(buffer, at, out, 0, got);
                position += got;
            }
            if (channelPosition != position) {
                channel.position(position);
            }
            while (true) {
                if (got == out.length) {
                    // Probe for growth past the size seen above; the common case ends here.
                    byte[] probe = new byte[1];
                    if (channel.read(ByteBuffer.wrap(probe)) <= 0) {
                        break;
                    }
                    out = Arrays.copyOf(out, Math.max(got + 1, Math.max(256, out.length * 2)));
                    out[got++] = probe[0];
                    position++;
                    continue;
                }
                int r = channel.read(ByteBuffer.wrap(out, got, out.length - got));
                if (r < 0) {
                    break;
                }
                got += r;
                position += r;
            }
            channelPosition = position;
            eof = true;
            return got == out.length ? out : Arrays.copyOf(out, got);
        }

        @Override
        void write(byte[] data) throws IOException {
            bufferLength = 0;
            channel.position(append ? channel.size() : position);
            ByteBuffer bb = ByteBuffer.wrap(data);
            while (bb.hasRemaining()) {
                channel.write(bb);
            }
            position += data.length;
            channelPosition = -1;
        }

        @Override
        long tell() {
            return position;
        }

        @Override
        boolean seek(long pos) {
            if (pos < 0) {
                return false;
            }
            position = pos;
            return true;
        }

        @Override
        long size() throws IOException {
            return channel.size();
        }

        @Override
        void truncate(long size) throws IOException {
            bufferLength = 0;
            channelPosition = -1;
            if (size < channel.size()) {
                channel.truncate(size);
            } else if (size > channel.size()) {
                channel.position(size - 1);
                channel.write(ByteBuffer.wrap(new byte[1]));
            }
        }

        @Override
        void close() throws IOException {
            channel.close();
        }

        @Override
        String wrapperType() {
            return "plainfile";
        }

        @Override
        String streamType() {
            return "STDIO";
        }
    }

    // ------------------------------------------------------------------ memory

    /** php://memory, php://temp and php://input: bytes in memory. */
    static final class MemoryStream extends PhpStream {

        private final String type;
        private byte[] data;
        private int size;
        private long position;

        MemoryStream(String mode, String uri, String type, byte[] initial) {
            super(mode, uri);
            this.type = type;
            this.data = initial == null ? new byte[256] : initial.clone();
            this.size = initial == null ? 0 : initial.length;
        }

        @Override
        int read(byte[] buf, int off, int len) {
            if (position >= size) {
                return -1;
            }
            int n = (int) Math.min(len, size - position);
            System.arraycopy(data, (int) position, buf, off, n);
            position += n;
            return n;
        }

        @Override
        void write(byte[] bytes) {
            int at = (int) position;
            int end = at + bytes.length;
            if (end > data.length) {
                data = Arrays.copyOf(data, Math.max(end, data.length * 2));
            }
            if (at > size) {
                Arrays.fill(data, size, at, (byte) 0);
            }
            System.arraycopy(bytes, 0, data, at, bytes.length);
            size = Math.max(size, end);
            position = end;
        }

        @Override
        long tell() {
            return position;
        }

        @Override
        boolean seek(long pos) {
            if (pos < 0) {
                return false;
            }
            position = pos;
            return true;
        }

        @Override
        long size() {
            return size;
        }

        @Override
        void truncate(long newSize) {
            if (newSize > data.length) {
                data = Arrays.copyOf(data, (int) newSize);
            }
            if (newSize > size) {
                Arrays.fill(data, size, (int) newSize, (byte) 0);
            }
            size = (int) newSize;
        }

        @Override
        String wrapperType() {
            return "PHP";
        }

        @Override
        String streamType() {
            return type;
        }
    }

    // ------------------------------------------------------------------ output

    /** php://output (through echo, so output buffering applies), php://stdout and php://stderr. */
    static final class OutputStreamStream extends PhpStream {

        private final PhpContext ctx;
        private final OutputStream raw;
        private long written;

        OutputStreamStream(PhpContext ctx, OutputStream raw, String mode, String uri) {
            super(mode.startsWith("r") && mode.indexOf('+') < 0 ? "wb" : mode, uri);
            this.ctx = ctx;
            this.raw = raw;
        }

        @Override
        int read(byte[] buf, int off, int len) {
            return -1;
        }

        @Override
        void write(byte[] bytes) throws IOException {
            if (raw == null) {
                ctx.echo(dev.phpruntime.runtime.PhpStrings.of(bytes));
            } else {
                // Keep order with echo output already waiting in the context.
                ctx.flushOutput();
                raw.write(bytes);
                raw.flush();
            }
            written += bytes.length;
        }

        @Override
        long tell() {
            return written;
        }

        @Override
        boolean seek(long pos) {
            return false;
        }

        @Override
        boolean seekable() {
            return false;
        }

        @Override
        long size() {
            return 0;
        }

        @Override
        String wrapperType() {
            return "PHP";
        }

        @Override
        String streamType() {
            return raw == null ? "Output" : "STDIO";
        }
    }

    // ------------------------------------------------------------------ directories

    /** The handle behind opendir(): the names in directory order, "." and ".." first. */
    static final class DirHandle {
        final java.util.List<String> names;
        int index;

        DirHandle(java.util.List<String> names) {
            this.names = names;
        }
    }
}
