package dev.phpruntime.host;

import dev.phpruntime.runtime.PhpError;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One response being built for one request. Headers are collected until the first
 * {@code write()}/{@code end()}/{@code sendfile()}, then the status line and header block are
 * written straight into one exactly sized byte array. For a small body the head and the body
 * share that array, so the common hello response is one allocation and one write. There are no
 * headers that the handler did not ask for, apart from Content-Length/Transfer-Encoding and
 * Connection where HTTP/1.1 needs them.
 */
public final class BlitzHttpResponseWriter {

    private static final java.nio.charset.Charset BYTES = StandardCharsets.ISO_8859_1;
    private static final byte[] TRANSFER_CHUNKED = "Transfer-Encoding: chunked\r\n".getBytes(BYTES);
    private static final byte[] CONNECTION_CLOSE = "Connection: close\r\n".getBytes(BYTES);
    private static final byte[] CONNECTION_KEEP_ALIVE = "Connection: keep-alive\r\n".getBytes(BYTES);
    private static final byte[] CONTENT_LENGTH = "Content-Length: ".getBytes(BYTES);
    private static final byte[] SET_COOKIE = "Set-Cookie: ".getBytes(BYTES);
    /** A body up to this size travels in the same array, and the same write, as the head. */
    private static final int COMBINE_LIMIT = 16 * 1024;
    /** One status line per code, built on first use; only about forty codes are ever used. */
    private static final byte[][] STATUS_LINES = new byte[600][];

    private final BlitzHttpConnection connection;
    private final BlitzHttpRequest request;
    private final List<String[]> headers = new ArrayList<>(4);
    private final List<String> cookies = new ArrayList<>(0);
    private int status = 200;
    private String reason;
    private boolean headersSent;
    private boolean chunked;
    private boolean ended;
    private boolean forceClose;

    BlitzHttpResponseWriter(BlitzHttpConnection connection, BlitzHttpRequest request) {
        this.connection = connection;
        this.request = request;
    }

    public int status() {
        return status;
    }

    public void setStatus(int code, String customReason) {
        if (code < 100 || code > 599) {
            throw new PhpError(PhpError.VALUE_ERROR,
                    "Blitz\\Http\\Response::status(): Argument #1 ($http_code) must be between 100 and 599", null);
        }
        if (customReason != null && !customReason.isEmpty()) {
            checked(customReason, "reason phrase");
        }
        status = code;
        reason = customReason == null || customReason.isEmpty() ? null : customReason;
    }

    public void setHeader(String name, String value) {
        checked(name, "header name");
        checked(value, "header value");
        if (name.isEmpty()) {
            throw new PhpError(PhpError.VALUE_ERROR,
                    "Blitz\\Http\\Response::header(): invalid header name", null);
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c <= 32 || c >= 127 || c == ':') {
                throw new PhpError(PhpError.VALUE_ERROR,
                        "Blitz\\Http\\Response::header(): invalid header name", null);
            }
        }
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i)[0].equalsIgnoreCase(name)) {
                headers.set(i, new String[]{name, value});
                return;
            }
        }
        headers.add(new String[]{name, value});
    }

    public void addCookie(String line) {
        checked(line, "cookie");
        cookies.add(line);
    }

    public boolean write(byte[] data) {
        if (ended) {
            return false;
        }
        if (data.length == 0) {
            return true;
        }
        if (!headersSent) {
            if (request.version().equals("HTTP/1.0")) {
                forceClose = true;
                sendHead(-1, false, true, true);
            } else {
                chunked = true;
                sendHead(-1, true, false, false);
            }
        }
        if (chunked) {
            connection.enqueue(chunkFrame(data));
        } else {
            connection.enqueue(data);
        }
        connection.flushWrites();
        return true;
    }

    public boolean end(byte[] body) {
        if (ended) {
            return false;
        }
        byte[] data = body == null ? new byte[0] : body;
        boolean noContent = status == 204 || status == 304;
        boolean suppressBody = noContent || request.head();
        if (!headersSent) {
            headersSent = true;
            boolean close = !request.keepAlive();
            if (suppressBody || data.length == 0) {
                int contentLength = noContent ? -1 : data.length;
                byte[] head = new byte[headSize(contentLength, false, noContent, close)];
                writeHead(head, 0, contentLength, false, noContent, close);
                connection.enqueue(head);
            } else if (data.length <= COMBINE_LIMIT) {
                int headSize = headSize(data.length, false, false, close);
                byte[] response = new byte[headSize + data.length];
                writeHead(response, 0, data.length, false, false, close);
                System.arraycopy(data, 0, response, headSize, data.length);
                connection.enqueue(response);
            } else {
                byte[] head = new byte[headSize(data.length, false, false, close)];
                writeHead(head, 0, data.length, false, false, close);
                connection.enqueue(head);
                connection.enqueue(data);
            }
        } else if (chunked) {
            if (!suppressBody && data.length > 0) {
                connection.enqueue(chunkFrame(data));
            }
            if (!suppressBody) {
                connection.enqueue(FINAL_CHUNK);
            }
        } else if (!suppressBody && data.length > 0) {
            connection.enqueue(data);
        }
        ended = true;
        if (!request.keepAlive() || forceClose) {
            connection.markCloseAfterFlush();
        }
        connection.flushWrites();
        return true;
    }

    public boolean sendFile(Path path, long offset, long length) {
        if (ended || headersSent) {
            return false;
        }
        if (!Files.isRegularFile(path) || offset < 0 || length < 0) {
            return false;
        }
        long size;
        try {
            size = Files.size(path);
        } catch (IOException e) {
            return false;
        }
        if (offset > size) {
            return false;
        }
        long remaining = length == 0 ? size - offset : Math.min(length, size - offset);
        FileChannel file;
        try {
            file = FileChannel.open(path, StandardOpenOption.READ);
        } catch (IOException e) {
            return false;
        }
        sendHead(remaining > Integer.MAX_VALUE ? -1 : (int) remaining, false, false,
                remaining > Integer.MAX_VALUE);
        if (remaining > 0 && !request.head()) {
            connection.enqueue(new BlitzHttpConnection.FileOut(file, offset, remaining));
        } else {
            try {
                file.close();
            } catch (IOException ignored) {
                // nothing was sent from it
            }
        }
        ended = true;
        if (!request.keepAlive()) {
            connection.markCloseAfterFlush();
        }
        connection.flushWrites();
        return true;
    }

    public void redirect(String url, int code) {
        setHeader("Location", url);
        setStatus(code, null);
        end(new byte[0]);
    }

    public boolean isWritable() {
        return !ended && connection.isOpen();
    }

    public boolean ended() {
        return ended;
    }

    /** The handler threw: answer 500 if nothing has been written yet. */
    public boolean fail(int code, String message) {
        if (ended || headersSent) {
            return false;
        }
        setStatus(code, null);
        setHeader("Content-Type", "text/plain; charset=UTF-8");
        ended = false; // end() must be allowed to run
        return end(message.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static final byte[] FINAL_CHUNK = "0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private void sendHead(int contentLength, boolean useChunked, boolean omitLength, boolean close) {
        headersSent = true;
        boolean closeConnection = close || !request.keepAlive();
        byte[] head = new byte[headSize(contentLength, useChunked, omitLength, closeConnection)];
        writeHead(head, 0, contentLength, useChunked, omitLength, closeConnection);
        connection.enqueue(head);
    }

    /** The exact number of bytes {@link #writeHead} writes, so the head needs no growth or copy. */
    private int headSize(int contentLength, boolean useChunked, boolean omitLength,
                         boolean closeConnection) {
        int size = statusLine().length;
        for (int i = 0; i < headers.size(); i++) {
            String[] header = headers.get(i);
            if (skipped(header[0])) {
                continue;
            }
            size += header[0].length() + header[1].length() + 4; // ": " and CRLF
        }
        for (int i = 0; i < cookies.size(); i++) {
            size += SET_COOKIE.length + cookies.get(i).length() + 2;
        }
        if (useChunked) {
            size += TRANSFER_CHUNKED.length;
        } else if (!omitLength && contentLength >= 0) {
            size += CONTENT_LENGTH.length + decimalLength(contentLength) + 2;
        }
        if (closeConnection) {
            size += CONNECTION_CLOSE.length;
        } else if (request.version().equals("HTTP/1.0")) {
            size += CONNECTION_KEEP_ALIVE.length;
        }
        return size + 2; // the empty line ending the head
    }

    /** Writes the head into {@code out} at {@code offset}; returns the number of bytes written. */
    private int writeHead(byte[] out, int offset, int contentLength, boolean useChunked,
                          boolean omitLength, boolean closeConnection) {
        int p = offset;
        byte[] statusLine = statusLine();
        System.arraycopy(statusLine, 0, out, p, statusLine.length);
        p += statusLine.length;
        for (int i = 0; i < headers.size(); i++) {
            String[] header = headers.get(i);
            if (skipped(header[0])) {
                continue;
            }
            p = putAscii(out, p, header[0]);
            out[p++] = ':';
            out[p++] = ' ';
            p = putAscii(out, p, header[1]);
            out[p++] = '\r';
            out[p++] = '\n';
        }
        for (int i = 0; i < cookies.size(); i++) {
            p = putBytes(out, p, SET_COOKIE);
            p = putAscii(out, p, cookies.get(i));
            out[p++] = '\r';
            out[p++] = '\n';
        }
        if (useChunked) {
            p = putBytes(out, p, TRANSFER_CHUNKED);
        } else if (!omitLength && contentLength >= 0) {
            p = putBytes(out, p, CONTENT_LENGTH);
            p = putDecimal(out, p, contentLength);
            out[p++] = '\r';
            out[p++] = '\n';
        }
        if (closeConnection) {
            p = putBytes(out, p, CONNECTION_CLOSE);
            connection.markCloseAfterFlush();
        } else if (request.version().equals("HTTP/1.0")) {
            p = putBytes(out, p, CONNECTION_KEEP_ALIVE);
        }
        out[p++] = '\r';
        out[p++] = '\n';
        return p - offset;
    }

    private static boolean skipped(String name) {
        return name.equalsIgnoreCase("Content-Length")
                || name.equalsIgnoreCase("Transfer-Encoding")
                || name.equalsIgnoreCase("Connection");
    }

    private byte[] statusLine() {
        if (reason != null) {
            return ("HTTP/1.1 " + status + " " + reason + "\r\n").getBytes(BYTES);
        }
        byte[] line = STATUS_LINES[status];
        if (line == null) {
            line = ("HTTP/1.1 " + status + " " + reason(status) + "\r\n").getBytes(BYTES);
            STATUS_LINES[status] = line;
        }
        return line;
    }

    private byte[] chunkFrame(byte[] data) {
        String hex = Integer.toHexString(data.length);
        int size = hex.length() + 2 + data.length + 2;
        byte[] frame = new byte[size];
        int p = 0;
        for (int i = 0; i < hex.length(); i++) {
            frame[p++] = (byte) hex.charAt(i);
        }
        frame[p++] = '\r';
        frame[p++] = '\n';
        System.arraycopy(data, 0, frame, p, data.length);
        p += data.length;
        frame[p++] = '\r';
        frame[p] = '\n';
        return frame;
    }

    public static String reason(int code) {
        return switch (code) {
            case 100 -> "Continue";
            case 101 -> "Switching Protocols";
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 206 -> "Partial Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 303 -> "See Other";
            case 304 -> "Not Modified";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 408 -> "Request Timeout";
            case 409 -> "Conflict";
            case 411 -> "Length Required";
            case 413 -> "Content Too Large";
            case 414 -> "URI Too Long";
            case 415 -> "Unsupported Media Type";
            case 422 -> "Unprocessable Content";
            case 429 -> "Too Many Requests";
            case 431 -> "Request Header Fields Too Large";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            case 505 -> "HTTP Version Not Supported";
            default -> "Unknown";
        };
    }

    private static void checked(String value, String what) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == '\0') {
                throw new PhpError(PhpError.VALUE_ERROR,
                        "Blitz\\Http\\Response: " + what + " may not contain CR, LF or NUL", null);
            }
        }
    }

    private static int putAscii(byte[] out, int offset, String value) {
        for (int i = 0; i < value.length(); i++) {
            out[offset++] = (byte) value.charAt(i);
        }
        return offset;
    }

    private static int putBytes(byte[] out, int offset, byte[] value) {
        System.arraycopy(value, 0, out, offset, value.length);
        return offset + value.length;
    }

    private static int putDecimal(byte[] out, int offset, int value) {
        int digits = decimalLength(value);
        int p = offset + digits - 1;
        int v = value;
        while (p >= offset) {
            out[p--] = (byte) ('0' + v % 10);
            v /= 10;
        }
        return offset + digits;
    }

    private static int decimalLength(int value) {
        if (value < 10) {
            return 1;
        }
        if (value < 100) {
            return 2;
        }
        if (value < 1_000) {
            return 3;
        }
        if (value < 10_000) {
            return 4;
        }
        if (value < 100_000) {
            return 5;
        }
        if (value < 1_000_000) {
            return 6;
        }
        if (value < 10_000_000) {
            return 7;
        }
        if (value < 100_000_000) {
            return 8;
        }
        if (value < 1_000_000_000) {
            return 9;
        }
        return 10;
    }
}
