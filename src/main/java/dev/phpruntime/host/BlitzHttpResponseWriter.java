package dev.phpruntime.host;

import dev.phpruntime.runtime.PhpError;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * One response being built for one request. Headers are collected until the first
 * {@code write()}/{@code end()}/{@code sendfile()}, then the status line and header block are
 * written straight into one byte array together with (for a normal response) the body. No
 * intermediate response object graph and no headers that the handler did not ask for, apart
 * from Content-Length/Transfer-Encoding and Connection where HTTP/1.1 needs them.
 */
public final class BlitzHttpResponseWriter {

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
            sendHead(noContent ? -1 : data.length, false, noContent, false);
            if (!suppressBody && data.length > 0) {
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
        return end(message.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    private static final byte[] FINAL_CHUNK = "0\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

    private void sendHead(int contentLength, boolean useChunked, boolean omitLength, boolean close) {
        headersSent = true;
        HeadBuilder head = new HeadBuilder(128);
        head.ascii("HTTP/1.1 ").ascii(Integer.toString(status)).space().ascii(reason()).crlf();
        for (String[] h : headers) {
            if (h[0].equalsIgnoreCase("Content-Length")
                    || h[0].equalsIgnoreCase("Transfer-Encoding")
                    || h[0].equalsIgnoreCase("Connection")) {
                continue;
            }
            head.ascii(h[0]).ascii(": ").ascii(h[1]).crlf();
        }
        for (String cookie : cookies) {
            head.ascii("Set-Cookie: ").ascii(cookie).crlf();
        }
        if (useChunked) {
            head.ascii("Transfer-Encoding: chunked").crlf();
        } else if (!omitLength && contentLength >= 0) {
            head.ascii("Content-Length: ").ascii(Integer.toString(contentLength)).crlf();
        }
        if (close || !request.keepAlive()) {
            head.ascii("Connection: close").crlf();
            connection.markCloseAfterFlush();
        } else if (request.version().equals("HTTP/1.0")) {
            head.ascii("Connection: keep-alive").crlf();
        }
        head.crlf();
        connection.enqueue(head.toArray());
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

    private String reason() {
        return reason != null ? reason : reason(status);
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

    /** A growable response head; strings are written as PHP's ISO-8859-1 bytes. */
    private static final class HeadBuilder {
        private byte[] data;
        private int length;

        HeadBuilder(int capacity) {
            data = new byte[Math.max(64, capacity)];
        }

        HeadBuilder ascii(String s) {
            if (length + s.length() > data.length) {
                grow(length + s.length());
            }
            for (int i = 0; i < s.length(); i++) {
                data[length++] = (byte) s.charAt(i);
            }
            return this;
        }

        HeadBuilder space() {
            return ascii(" ");
        }

        HeadBuilder crlf() {
            return ascii("\r\n");
        }

        private void grow(int need) {
            int want = Math.max(need, data.length + (data.length >> 1));
            byte[] grown = new byte[want];
            System.arraycopy(data, 0, grown, 0, length);
            data = grown;
        }

        byte[] toArray() {
            byte[] out = new byte[length];
            System.arraycopy(data, 0, out, 0, length);
            return out;
        }
    }
}
