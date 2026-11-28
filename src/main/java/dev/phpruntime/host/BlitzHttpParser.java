package dev.phpruntime.host;

import dev.phpruntime.runtime.PhpStrings;

/**
 * Incremental HTTP/1.1 request parser over one connection's byte window.
 *
 * <p>Headers are scanned once for the fields that decide framing (Content-Length,
 * Transfer-Encoding, Connection, Expect); the header block itself is copied raw and turned into
 * strings only when the handler reads {@code $request->header} or {@code $request->server}.
 * Fixed-length bodies are copied into their final array as they arrive; chunked bodies are
 * assembled from the chunk frames. Both leave the remaining bytes in place, so pipelined
 * requests are parsed in order.
 */
final class BlitzHttpParser {

    private static final int MAX_REQUEST_LINE = 8 * 1024;
    private static final int MAX_HEADER_BYTES = 32 * 1024;
    private static final int MAX_HEADERS = 128;
    private static final int MAX_CHUNK_LINE = 4 * 1024;

    private static final int HEADERS = 0;
    private static final int FIXED = 1;
    private static final int CHUNK_SIZE = 2;
    private static final int CHUNK_DATA = 3;
    private static final int CHUNK_TRAILERS = 4;

    private final BlitzHttpConnection owner;

    private int state = HEADERS;
    private String method;
    private String target;
    private String path;
    private String query;
    private String version;
    private byte[] headerBlock;
    private boolean head;
    private boolean keepAlive;
    private boolean expectContinue;
    private boolean continuePending;

    private int contentLength;
    private byte[] body;
    private int bodyPos;

    private BlitzByteArray chunkBody;
    private int chunkRemaining;
    private int trailerBytes;

    BlitzHttpParser(BlitzHttpConnection owner) {
        this.owner = owner;
    }

    /** True once, after headers announced a body and asked for a 100 Continue. */
    boolean takeContinuePending() {
        boolean pending = continuePending;
        continuePending = false;
        return pending;
    }

    /** The next complete request, or null when more bytes are needed. */
    BlitzHttpRequest parse(BlitzByteArray in) throws BlitzHttpException {
        while (true) {
            int beforeState = state;
            int beforeStart = in.start;
            BlitzHttpRequest completed = switch (state) {
                case HEADERS -> parseHeaders(in);
                case FIXED -> parseFixed(in);
                case CHUNK_SIZE -> parseChunkSize(in);
                case CHUNK_DATA -> parseChunkData(in);
                case CHUNK_TRAILERS -> parseChunkTrailers(in);
                default -> null;
            };
            if (completed != null) {
                return completed;
            }
            if (state == beforeState && in.start == beforeStart) {
                return null;
            }
        }
    }

    private BlitzHttpRequest parseHeaders(BlitzByteArray in) throws BlitzHttpException {
        int start = in.start;
        int lineFeed = indexOf(in.data, start, in.end, (byte) '\n');
        if (lineFeed < 0) {
            if (in.readable() > MAX_REQUEST_LINE) {
                throw new BlitzHttpException(414, "request line too long");
            }
            return null;
        }
        int lineEnd = lineFeed > start && in.data[lineFeed - 1] == '\r' ? lineFeed - 1 : lineFeed;
        String requestLine = PhpStrings.of(in.data, start, lineEnd - start);
        int firstSpace = requestLine.indexOf(' ');
        if (firstSpace <= 0) {
            throw new BlitzHttpException(400, "malformed request line");
        }
        int secondSpace = requestLine.indexOf(' ', firstSpace + 1);
        if (secondSpace <= firstSpace + 1) {
            throw new BlitzHttpException(400, "malformed request line");
        }
        method = requestLine.substring(0, firstSpace);
        target = requestLine.substring(firstSpace + 1, secondSpace);
        version = requestLine.substring(secondSpace + 1).trim();
        if (!version.equals("HTTP/1.1") && !version.equals("HTTP/1.0")) {
            throw new BlitzHttpException(505, "unsupported HTTP version");
        }
        if (!isToken(method)) {
            throw new BlitzHttpException(400, "invalid method");
        }
        head = method.equals("HEAD");
        normalizeTarget();

        int headerStart = lineFeed + 1;
        int cursor = headerStart;
        int headerEnd = -1;
        int headerCount = 0;
        long seenContentLength = -1;
        boolean connectionClose = false;
        boolean connectionKeepAlive = false;
        boolean transferChunked = false;
        boolean transferOther = false;
        int length = 0;
        while (true) {
            int lf = indexOf(in.data, cursor, in.end, (byte) '\n');
            if (lf < 0) {
                if (in.readable() > MAX_HEADER_BYTES) {
                    throw new BlitzHttpException(431, "header block too large");
                }
                return null;
            }
            int end = lf > cursor && in.data[lf - 1] == '\r' ? lf - 1 : lf;
            if (end == cursor) {
                headerEnd = lf + 1;
                break;
            }
            headerCount++;
            if (headerCount > MAX_HEADERS) {
                throw new BlitzHttpException(431, "too many headers");
            }
            int colon = indexOf(in.data, cursor, end, (byte) ':');
            if (colon < 0 || colon > end) {
                throw new BlitzHttpException(400, "header without colon");
            }
            int nameStart = cursor;
            int nameEnd = colon;
            while (nameEnd > nameStart && (in.data[nameEnd - 1] == ' ' || in.data[nameEnd - 1] == '\t')) {
                nameEnd--;
            }
            if (nameEnd == nameStart) {
                throw new BlitzHttpException(400, "empty header name");
            }
            int valueStart = colon + 1;
            while (valueStart < end && (in.data[valueStart] == ' ' || in.data[valueStart] == '\t')) {
                valueStart++;
            }
            int valueEnd = end;
            while (valueEnd > valueStart
                    && (in.data[valueEnd - 1] == ' ' || in.data[valueEnd - 1] == '\t')) {
                valueEnd--;
            }
            if (headerNameEquals(in.data, nameStart, nameEnd, "content-length")) {
                long parsed = parseDecimal(in.data, valueStart, valueEnd);
                if (parsed < 0 || parsed > Integer.MAX_VALUE) {
                    throw new BlitzHttpException(400, "invalid Content-Length");
                }
                if (seenContentLength >= 0 && seenContentLength != parsed) {
                    throw new BlitzHttpException(400, "conflicting Content-Length");
                }
                seenContentLength = parsed;
            } else if (headerNameEquals(in.data, nameStart, nameEnd, "transfer-encoding")) {
                if (containsToken(in.data, valueStart, valueEnd, "chunked")) {
                    transferChunked = true;
                } else {
                    transferOther = true;
                }
            } else if (headerNameEquals(in.data, nameStart, nameEnd, "connection")) {
                if (containsToken(in.data, valueStart, valueEnd, "close")) {
                    connectionClose = true;
                }
                if (containsToken(in.data, valueStart, valueEnd, "keep-alive")) {
                    connectionKeepAlive = true;
                }
            } else if (headerNameEquals(in.data, nameStart, nameEnd, "expect")) {
                if (equalsIgnoreCase(in.data, valueStart, valueEnd, "100-continue")) {
                    expectContinue = true;
                }
            }
            length += lf - cursor + 1;
            if (length > MAX_HEADER_BYTES) {
                throw new BlitzHttpException(431, "header block too large");
            }
            cursor = lf + 1;
        }

        if (transferChunked && seenContentLength >= 0) {
            // A message with both framings is a request-smuggling shape; refuse it.
            throw new BlitzHttpException(400, "both Transfer-Encoding and Content-Length");
        }
        if (transferOther && !transferChunked) {
            throw new BlitzHttpException(501, "unsupported Transfer-Encoding");
        }
        contentLength = (int) Math.max(0, seenContentLength);
        boolean http10 = version.equals("HTTP/1.0");
        keepAlive = !connectionClose && (!http10 || connectionKeepAlive);
        headerBlock = in.copy(headerStart - start, headerEnd - headerStart);
        in.start = headerEnd;

        if (transferChunked) {
            state = CHUNK_SIZE;
            chunkBody = new BlitzByteArray(Math.min(4096, Math.max(64, contentLength)));
            continuePending = expectContinue;
            return null;
        }
        if (contentLength > 0) {
            if (contentLength > owner.maxBodyBytes()) {
                throw new BlitzHttpException(413, "request body too large");
            }
            body = new byte[contentLength];
            bodyPos = 0;
            state = FIXED;
            continuePending = expectContinue;
            return null;
        }
        continuePending = false;
        return buildRequest(null);
    }

    private BlitzHttpRequest parseFixed(BlitzByteArray in) throws BlitzHttpException {
        int remaining = contentLength - bodyPos;
        if (in.readable() < remaining) {
            return null;
        }
        System.arraycopy(in.data, in.start, body, bodyPos, remaining);
        bodyPos = contentLength;
        in.consume(remaining);
        return buildRequest(body);
    }

    private BlitzHttpRequest parseChunkSize(BlitzByteArray in) throws BlitzHttpException {
        int lf = indexOf(in.data, in.start, in.end, (byte) '\n');
        if (lf < 0) {
            if (in.readable() > MAX_CHUNK_LINE) {
                throw new BlitzHttpException(400, "chunk size line too long");
            }
            return null;
        }
        int end = lf > in.start && in.data[lf - 1] == '\r' ? lf - 1 : lf;
        int valueEnd = end;
        int semicolon = indexOf(in.data, in.start, end, (byte) ';');
        if (semicolon >= 0 && semicolon < valueEnd) {
            valueEnd = semicolon;
        }
        long size = parseHex(in.data, in.start, valueEnd);
        if (size < 0) {
            throw new BlitzHttpException(400, "invalid chunk size");
        }
        in.start = lf + 1;
        if (size == 0) {
            state = CHUNK_TRAILERS;
            return null;
        }
        if (size > owner.maxBodyBytes() - chunkBody.readable()) {
            throw new BlitzHttpException(413, "request body too large");
        }
        chunkRemaining = (int) size;
        state = CHUNK_DATA;
        return null;
    }

    private BlitzHttpRequest parseChunkData(BlitzByteArray in) throws BlitzHttpException {
        if (in.readable() < chunkRemaining + 1) {
            return null;
        }
        int after = in.start + chunkRemaining;
        int eol;
        if (in.data[after] == '\r') {
            if (in.readable() < chunkRemaining + 2) {
                return null;
            }
            if (in.data[after + 1] != '\n') {
                throw new BlitzHttpException(400, "malformed chunk terminator");
            }
            eol = 2;
        } else if (in.data[after] == '\n') {
            eol = 1;
        } else {
            throw new BlitzHttpException(400, "malformed chunk terminator");
        }
        chunkBody.ensure(chunkRemaining);
        System.arraycopy(in.data, in.start, chunkBody.data, chunkBody.end, chunkRemaining);
        chunkBody.end += chunkRemaining;
        in.consume(chunkRemaining + eol);
        state = CHUNK_SIZE;
        return null;
    }

    private BlitzHttpRequest parseChunkTrailers(BlitzByteArray in) throws BlitzHttpException {
        while (true) {
            int lf = indexOf(in.data, in.start, in.end, (byte) '\n');
            if (lf < 0) {
                return null;
            }
            int end = lf > in.start && in.data[lf - 1] == '\r' ? lf - 1 : lf;
            boolean empty = end == in.start;
            trailerBytes += lf + 1 - in.start;
            if (trailerBytes > MAX_HEADER_BYTES) {
                throw new BlitzHttpException(431, "chunk trailers too large");
            }
            in.start = lf + 1;
            if (empty) {
                byte[] assembled = chunkBody.toArray();
                chunkBody = null;
                return buildRequest(assembled);
            }
        }
    }

    private BlitzHttpRequest buildRequest(byte[] requestBody) {
        long now = System.currentTimeMillis();
        BlitzHttpRequest request = new BlitzHttpRequest(method, target, path, query, version,
                headerBlock, requestBody == null || requestBody.length == 0 ? null : requestBody,
                owner.remoteAddr(), owner.remotePort(), owner.localAddr(), owner.localPort(),
                owner.id(), now, owner.createdMillis(), keepAlive, head,
                owner.scriptFilename(), owner.documentRoot());
        reset();
        return request;
    }

    private void reset() {
        state = HEADERS;
        method = null;
        target = null;
        path = null;
        query = null;
        version = null;
        headerBlock = null;
        head = false;
        keepAlive = false;
        expectContinue = false;
        continuePending = false;
        contentLength = 0;
        body = null;
        bodyPos = 0;
        chunkBody = null;
        chunkRemaining = 0;
        trailerBytes = 0;
    }

    private void normalizeTarget() throws BlitzHttpException {
        String normalized = target;
        if (normalized.regionMatches(true, 0, "http://", 0, 7)
                || normalized.regionMatches(true, 0, "https://", 0, 8)) {
            int schemeEnd = normalized.indexOf("//") + 2;
            int slash = normalized.indexOf('/', schemeEnd);
            normalized = slash < 0 ? "/" : normalized.substring(slash);
        }
        if (!normalized.startsWith("/") && !normalized.equals("*")) {
            throw new BlitzHttpException(400, "invalid request target");
        }
        target = normalized;
        int q = normalized.indexOf('?');
        path = q < 0 ? normalized : normalized.substring(0, q);
        query = q < 0 ? "" : normalized.substring(q + 1);
    }

    private static boolean isToken(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 32 || c >= 127 || "()<>@,;:\\\"/[]?={} \t".indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean headerNameEquals(byte[] data, int start, int end, String lowerName) {
        if (end - start != lowerName.length()) {
            return false;
        }
        for (int i = 0; i < lowerName.length(); i++) {
            byte b = data[start + i];
            char c = b >= 'A' && b <= 'Z' ? (char) (b + ('a' - 'A')) : (char) (b & 0xff);
            if (c != lowerName.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean equalsIgnoreCase(byte[] data, int start, int end, String value) {
        if (end - start != value.length()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            byte b = data[start + i];
            char c = b >= 'A' && b <= 'Z' ? (char) (b + ('a' - 'A')) : (char) (b & 0xff);
            if (c != value.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** Comma-separated token list, case-insensitive, with optional whitespace. */
    private static boolean containsToken(byte[] data, int start, int end, String token) {
        int i = start;
        while (i < end) {
            while (i < end && (data[i] == ' ' || data[i] == '\t' || data[i] == ',')) {
                i++;
            }
            int tokenStart = i;
            while (i < end && data[i] != ',' && data[i] != ' ' && data[i] != '\t') {
                i++;
            }
            if (i > tokenStart && equalsIgnoreCase(data, tokenStart, i, token)) {
                return true;
            }
            while (i < end && data[i] != ',') {
                i++;
            }
        }
        return false;
    }

    private static int indexOf(byte[] data, int from, int to, byte value) {
        for (int i = from; i < to && i < data.length; i++) {
            if (data[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static long parseDecimal(byte[] data, int start, int end) {
        if (start >= end) {
            return -1;
        }
        long value = 0;
        for (int i = start; i < end; i++) {
            int c = data[i] - '0';
            if (c < 0 || c > 9) {
                return -1;
            }
            value = value * 10 + c;
            if (value > 1L << 40) {
                return -1;
            }
        }
        return value;
    }

    private static long parseHex(byte[] data, int start, int end) {
        while (start < end && (data[start] == ' ' || data[start] == '\t')) {
            start++;
        }
        while (end > start && (data[end - 1] == ' ' || data[end - 1] == '\t')) {
            end--;
        }
        if (start >= end) {
            return -1;
        }
        long value = 0;
        for (int i = start; i < end; i++) {
            int c = data[i];
            int digit;
            if (c >= '0' && c <= '9') {
                digit = c - '0';
            } else if (c >= 'a' && c <= 'f') {
                digit = c - 'a' + 10;
            } else if (c >= 'A' && c <= 'F') {
                digit = c - 'A' + 10;
            } else {
                return -1;
            }
            value = value * 16 + digit;
            if (value > Integer.MAX_VALUE) {
                return -1;
            }
        }
        return value;
    }
}
