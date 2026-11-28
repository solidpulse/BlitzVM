package dev.phpruntime.host;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;

/**
 * One client connection on one worker's selector. The handler runs on the worker thread that
 * read the request, so a slow handler stalls only the other connections on that same worker.
 */
final class BlitzHttpConnection {

    private static final int READ_CHUNK = 16 * 1024;
    private static final int MAX_READ_PER_EVENT = 256 * 1024;
    private static final byte[] CONTINUE_100 =
            "HTTP/1.1 100 Continue\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

    private final SocketChannel channel;
    private final BlitzServerWorker worker;
    private final BlitzByteArray input = new BlitzByteArray();
    private final BlitzHttpParser parser;
    private final ArrayDeque<Outbound> outbound = new ArrayDeque<>();
    private final long id;
    private final String remoteAddr;
    private final int remotePort;
    private final String localAddr;
    private final int localPort;
    private final long createdMillis;
    private final int maxBodyBytes;
    private final long idleTimeoutMillis;
    private final int bufferOutputBytes;
    private final String scriptFilename;
    private final String documentRoot;

    private SelectionKey key;
    private boolean inputClosed;
    private boolean closeAfterFlush;
    private boolean closed;
    private boolean inFlush;
    private boolean processing;
    private boolean readEnabled = true;
    private boolean writeArmed;
    private long lastActiveNanos = System.nanoTime();
    private long queuedBytes;
    /** One response writer per connection; reused while the connection stays open. */
    private BlitzHttpResponseWriter responseWriter;
    /**
     * The common response is one byte array and the queue is empty: keep it here so a hello
     * request does not allocate a queue node and a wrapper for it. Anything behind it uses
     * {@link #outbound}.
     */
    private byte[] pendingBytes;
    private int pendingOffset;

    BlitzHttpConnection(SocketChannel channel, BlitzServerWorker worker, long id, int maxBodyBytes,
                        long idleTimeoutMillis, int bufferOutputBytes, String scriptFilename,
                        String documentRoot) {
        this.channel = channel;
        this.worker = worker;
        this.id = id;
        this.parser = new BlitzHttpParser(this);
        this.createdMillis = System.currentTimeMillis();
        this.maxBodyBytes = maxBodyBytes;
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.bufferOutputBytes = bufferOutputBytes;
        this.scriptFilename = scriptFilename;
        this.documentRoot = documentRoot;
        SocketAddress remote = channel.socket().getRemoteSocketAddress();
        SocketAddress local = channel.socket().getLocalSocketAddress();
        if (remote instanceof InetSocketAddress inet) {
            this.remoteAddr = inet.getAddress().getHostAddress();
            this.remotePort = inet.getPort();
        } else {
            this.remoteAddr = "127.0.0.1";
            this.remotePort = 0;
        }
        if (local instanceof InetSocketAddress inet) {
            this.localAddr = inet.getAddress().getHostAddress();
            this.localPort = inet.getPort();
        } else {
            this.localAddr = "127.0.0.1";
            this.localPort = 0;
        }
    }

    void attach(SelectionKey key) {
        this.key = key;
    }

    long id() {
        return id;
    }

    String remoteAddr() {
        return remoteAddr;
    }

    int remotePort() {
        return remotePort;
    }

    String localAddr() {
        return localAddr;
    }

    int localPort() {
        return localPort;
    }

    long createdMillis() {
        return createdMillis;
    }

    /** The writer for the next request on this connection, allocated on first use. */
    BlitzHttpResponseWriter responseWriter(BlitzHttpRequest request) {
        BlitzHttpResponseWriter writer = responseWriter;
        if (writer == null) {
            writer = new BlitzHttpResponseWriter(this, request);
            responseWriter = writer;
        } else {
            writer.reset(request);
        }
        return writer;
    }

    int maxBodyBytes() {
        return maxBodyBytes;
    }

    String scriptFilename() {
        return scriptFilename;
    }

    String documentRoot() {
        return documentRoot;
    }

    boolean isOpen() {
        return !closed && channel.isOpen();
    }

    void onReadable() {
        if (closed) {
            return;
        }
        try {
            if (!inputClosed) {
                int total = 0;
                while (total < MAX_READ_PER_EVENT) {
                    input.ensure(READ_CHUNK);
                    ByteBuffer view = input.writeBuffer();
                    int n = channel.read(view);
                    if (n < 0) {
                        inputClosed = true;
                        break;
                    }
                    if (n == 0) {
                        break;
                    }
                    input.end += n;
                    total += n;
                }
            }
        } catch (IOException e) {
            close();
            return;
        }
        process();
        flushWrites();
        if (closed) {
            return;
        }
        lastActiveNanos = System.nanoTime();
        if (inputClosed && !hasPendingOutput()) {
            close();
        }
    }

    private void process() {
        if (processing) {
            return;
        }
        processing = true;
        try {
            processRequests();
        } finally {
            processing = false;
        }
    }

    private void processRequests() {
        while (!closed) {
            if (isBackpressured()) {
                return;
            }
            BlitzHttpRequest request;
            try {
                request = parser.parse(input);
            } catch (BlitzHttpException e) {
                sendError(e.status);
                return;
            }
            if (parser.takeContinuePending()) {
                enqueue(CONTINUE_100);
                flushWrites();
            }
            if (request == null) {
                if (inputClosed && !hasPendingOutput()) {
                    close();
                } else if (inputClosed) {
                    closeAfterFlush = true;
                }
                return;
            }
            worker.handle(request, this);
            if (!request.keepAlive() || closeAfterFlush) {
                closeAfterFlush = true;
                return;
            }
            // The request's header block is a view into this window, so it is only reclaimed
            // after the handler has run.
            input.compact();
        }
    }

    void onWritable() {
        flushWrites();
        if (!closed) {
            lastActiveNanos = System.nanoTime();
        }
        if (!closed && !isBackpressured() && input.readable() > 0) {
            // Reading stopped while this connection was over the output limit; there may be
            // pipelined requests in the window that nobody parsed yet.
            process();
            flushWrites();
        }
    }

    /** True while the outbound queue is over {@code buffer_output_size}. */
    boolean isBackpressured() {
        return bufferOutputBytes > 0 && queuedBytes > bufferOutputBytes;
    }

    /** Closes a keep-alive connection that has been quiet for the configured timeout. */
    boolean closeIfIdle(long nowNanos) {
        if (idleTimeoutMillis <= 0 || closed || processing || input.readable() > 0
                || hasPendingOutput()) {
            return false;
        }
        if (nowNanos - lastActiveNanos < idleTimeoutMillis * 1_000_000L) {
            return false;
        }
        close();
        return true;
    }

    private boolean hasPendingOutput() {
        return pendingBytes != null || !outbound.isEmpty();
    }

    void markCloseAfterFlush() {
        closeAfterFlush = true;
    }

    void enqueue(byte[] bytes) {
        if (closed || bytes.length == 0) {
            return;
        }
        queuedBytes += bytes.length;
        if (pendingBytes == null && outbound.isEmpty()) {
            pendingBytes = bytes;
            pendingOffset = 0;
        } else {
            outbound.add(new BytesOut(ByteBuffer.wrap(bytes)));
        }
        updateReadArm();
    }

    void enqueue(FileOut file) {
        if (closed) {
            file.close();
            return;
        }
        queuedBytes += file.size();
        outbound.add(file);
        updateReadArm();
    }

    /** Stops reading from a client that is not draining its responses, and resumes when it does. */
    private void updateReadArm() {
        boolean backpressured = isBackpressured();
        if (backpressured && readEnabled) {
            readEnabled = false;
            applyInterest();
        } else if (!backpressured && !readEnabled) {
            readEnabled = true;
            applyInterest();
        }
    }

    /** Writes as much of the queued response as the socket accepts, then arms OP_WRITE. */
    void flushWrites() {
        if (closed || inFlush) {
            return;
        }
        inFlush = true;
        try {
            while (true) {
                if (pendingBytes != null) {
                    ByteBuffer buffer = ByteBuffer.wrap(pendingBytes, pendingOffset,
                            pendingBytes.length - pendingOffset);
                    while (buffer.hasRemaining()) {
                        int n;
                        try {
                            n = channel.write(buffer);
                        } catch (IOException e) {
                            close();
                            return;
                        }
                        if (n == 0) {
                            pendingOffset = buffer.position();
                            if (!writeArmed) {
                                writeArmed = true;
                                applyInterest();
                            }
                            return;
                        }
                    }
                    queuedBytes -= pendingBytes.length;
                    pendingBytes = null;
                    pendingOffset = 0;
                    drained();
                    continue;
                }
                if (outbound.isEmpty()) {
                    break;
                }
                Outbound item = outbound.peek();
                boolean done;
                try {
                    done = item.write(channel);
                } catch (IOException e) {
                    close();
                    return;
                }
                if (!done) {
                    if (!writeArmed) {
                        writeArmed = true;
                        applyInterest();
                    }
                    return;
                }
                outbound.poll();
                queuedBytes -= item.size();
                item.close();
                drained();
            }
            if (writeArmed) {
                writeArmed = false;
                applyInterest();
            }
            if (closeAfterFlush) {
                close();
            }
        } finally {
            inFlush = false;
        }
    }

    private void drained() {
        if (!readEnabled && queuedBytes <= bufferOutputBytes) {
            readEnabled = true;
            applyInterest();
        }
    }

    private void applyInterest() {
        if (key == null || !key.isValid()) {
            return;
        }
        int ops = 0;
        if (readEnabled && !inputClosed) {
            ops |= SelectionKey.OP_READ;
        }
        if (writeArmed) {
            ops |= SelectionKey.OP_WRITE;
        }
        if (ops != key.interestOps()) {
            key.interestOps(ops);
        }
    }

    private void sendError(int status) {
        String reason = BlitzHttpResponseWriter.reason(status);
        String body = reason + "\n";
        byte[] bodyBytes = body.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] head = ("HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n\r\n")
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] response = new byte[head.length + bodyBytes.length];
        System.arraycopy(head, 0, response, 0, head.length);
        System.arraycopy(bodyBytes, 0, response, head.length, bodyBytes.length);
        enqueue(response);
        closeAfterFlush = true;
        flushWrites();
    }

    /** Closes now. Anything still queued is lost; callers that need delivery use closeAfterFlush. */
    void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (key != null) {
            key.cancel();
        }
        for (Outbound item : outbound) {
            item.close();
        }
        outbound.clear();
        pendingBytes = null;
        pendingOffset = 0;
        queuedBytes = 0;
        writeArmed = false;
        try {
            channel.close();
        } catch (IOException ignored) {
            // the connection is gone either way
        }
    }

    interface Outbound {
        /** Bytes this item adds to the connection's outbound queue. */
        long size();

        /** Writes what the socket accepts; true when the item is fully written. */
        boolean write(SocketChannel channel) throws IOException;

        void close();
    }

    static final class BytesOut implements Outbound {
        private final ByteBuffer buffer;
        private final int size;

        BytesOut(ByteBuffer buffer) {
            this.buffer = buffer;
            this.size = buffer.remaining();
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public boolean write(SocketChannel channel) throws IOException {
            while (buffer.hasRemaining()) {
                int n = channel.write(buffer);
                if (n == 0) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void close() {
            // a heap ByteBuffer owns nothing
        }
    }

    /** A file queued by {@code $response->sendfile()}; the channel may use sendfile. */
    static final class FileOut implements Outbound {
        private final FileChannel file;
        private final long size;
        private long position;
        private long remaining;

        FileOut(FileChannel file, long position, long remaining) {
            this.file = file;
            this.size = remaining;
            this.position = position;
            this.remaining = remaining;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public boolean write(SocketChannel channel) throws IOException {
            while (remaining > 0) {
                long n = file.transferTo(position, remaining, channel);
                if (n <= 0) {
                    return false;
                }
                position += n;
                remaining -= n;
            }
            return true;
        }

        @Override
        public void close() {
            try {
                file.close();
            } catch (IOException ignored) {
                // the transfer is over
            }
        }
    }
}
