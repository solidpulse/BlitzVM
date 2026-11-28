package dev.phpruntime.host;

import java.nio.ByteBuffer;

/**
 * A growable byte window for one connection. The unread bytes live in
 * {@code data[start..end)}; a read appends at {@code end} without moving the window, so a
 * request that arrives in one read is parsed in place. Only pipelining or a partial request
 * needs the move to the front that {@link #compact()} performs.
 */
final class BlitzByteArray {

    private static final int INITIAL = 4096;
    private static final int SHRINK_ABOVE = 64 * 1024;
    private static final int SHRINK_KEEP = 16 * 1024;

    byte[] data;
    int start;
    int end;

    BlitzByteArray() {
        this(INITIAL);
    }

    BlitzByteArray(int capacity) {
        data = new byte[Math.max(64, capacity)];
    }

    int readable() {
        return end - start;
    }

    byte at(int offset) {
        return data[start + offset];
    }

    /** Makes room for {@code extra} more bytes after {@link #end}. */
    void ensure(int extra) {
        if (end + extra <= data.length) {
            return;
        }
        if (start > 0) {
            compact();
            if (end + extra <= data.length) {
                return;
            }
        }
        int want = Math.max(end + extra, data.length + (data.length >> 1));
        byte[] grown = new byte[want];
        System.arraycopy(data, start, grown, 0, end - start);
        end -= start;
        start = 0;
        data = grown;
    }

    /** A writable view of the free tail, for {@code SocketChannel.read}. The caller adds to end. */
    ByteBuffer writeBuffer() {
        return ByteBuffer.wrap(data, end, data.length - end);
    }

    /** Moves unread bytes to the front; shrinks a buffer that one large request grew. */
    void compact() {
        if (start == 0) {
            return;
        }
        int remaining = end - start;
        if (remaining > 0) {
            System.arraycopy(data, start, data, 0, remaining);
        }
        start = 0;
        end = remaining;
        if (data.length > SHRINK_ABOVE && remaining < SHRINK_KEEP) {
            byte[] smaller = new byte[SHRINK_KEEP];
            System.arraycopy(data, 0, smaller, 0, remaining);
            data = smaller;
        }
    }

    void consume(int bytes) {
        start += bytes;
        if (start == end) {
            start = 0;
            end = 0;
        }
    }

    byte[] copy(int offset, int length) {
        byte[] out = new byte[length];
        System.arraycopy(data, start + offset, out, 0, length);
        return out;
    }

    byte[] toArray() {
        byte[] out = new byte[end - start];
        System.arraycopy(data, start, out, 0, out.length);
        return out;
    }

    /** The line ending at {@code lf} (exclusive), with a preceding CR dropped. */
    int lineEnd(int lf) {
        return lf > start && data[lf - 1] == '\r' ? lf - 1 : lf;
    }
}
