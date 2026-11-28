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
    private final String scriptFilename;
    private final String documentRoot;

    private SelectionKey key;
    private boolean inputClosed;
    private boolean closeAfterFlush;
    private boolean closed;
    private boolean inFlush;

    BlitzHttpConnection(SocketChannel channel, BlitzServerWorker worker, long id, int maxBodyBytes,
                        String scriptFilename, String documentRoot) {
        this.channel = channel;
        this.worker = worker;
        this.id = id;
        this.parser = new BlitzHttpParser(this);
        this.createdMillis = System.currentTimeMillis();
        this.maxBodyBytes = maxBodyBytes;
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
        if (inputClosed && outbound.isEmpty()) {
            close();
        }
    }

    private void process() {
        while (!closed) {
            BlitzHttpRequest request;
            try {
                request = parser.parse(input);
            } catch (BlitzHttpException e) {
                sendError(e.status);
                return;
            }
            if (parser.takeContinuePending()) {
                outbound.add(new BytesOut(ByteBuffer.wrap(CONTINUE_100)));
                flushWrites();
            }
            if (request == null) {
                if (inputClosed && outbound.isEmpty()) {
                    close();
                } else if (inputClosed) {
                    closeAfterFlush = true;
                }
                return;
            }
            input.compact();
            worker.handle(request, this);
            if (!request.keepAlive() || closeAfterFlush) {
                closeAfterFlush = true;
                return;
            }
        }
    }

    void onWritable() {
        flushWrites();
    }

    void markCloseAfterFlush() {
        closeAfterFlush = true;
    }

    void enqueue(byte[] bytes) {
        if (closed || bytes.length == 0) {
            return;
        }
        outbound.add(new BytesOut(ByteBuffer.wrap(bytes)));
    }

    void enqueue(ByteBuffer buffer) {
        if (closed || !buffer.hasRemaining()) {
            return;
        }
        outbound.add(new BytesOut(buffer));
    }

    void enqueue(FileOut file) {
        if (closed) {
            file.close();
            return;
        }
        outbound.add(file);
    }

    /** Writes as much of the queued response as the socket accepts, then arms OP_WRITE. */
    void flushWrites() {
        if (closed || inFlush) {
            return;
        }
        inFlush = true;
        try {
            while (!outbound.isEmpty()) {
                Outbound item = outbound.peek();
                boolean done;
                try {
                    done = item.write(channel);
                } catch (IOException e) {
                    close();
                    return;
                }
                if (!done) {
                    arm(true);
                    return;
                }
                outbound.poll();
                item.close();
            }
            arm(false);
            if (closeAfterFlush) {
                close();
            }
        } finally {
            inFlush = false;
        }
    }

    private void arm(boolean write) {
        if (key == null || !key.isValid()) {
            return;
        }
        int ops = key.interestOps();
        int wanted = write ? ops | SelectionKey.OP_WRITE : ops & ~SelectionKey.OP_WRITE;
        if (wanted != ops) {
            key.interestOps(wanted);
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
        outbound.add(new BytesOut(ByteBuffer.wrap(response)));
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
        try {
            channel.close();
        } catch (IOException ignored) {
            // the connection is gone either way
        }
    }

    interface Outbound {
        /** Writes what the socket accepts; true when the item is fully written. */
        boolean write(SocketChannel channel) throws IOException;

        void close();
    }

    static final class BytesOut implements Outbound {
        private final ByteBuffer buffer;

        BytesOut(ByteBuffer buffer) {
            this.buffer = buffer;
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
        private long position;
        private long remaining;

        FileOut(FileChannel file, long position, long remaining) {
            this.file = file;
            this.position = position;
            this.remaining = remaining;
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
