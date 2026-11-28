package dev.phpruntime.host;

import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpQuery;
import dev.phpruntime.runtime.PhpStrings;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One parsed HTTP request. The request line is decoded at parse time because the connection
 * needs it; the header block and body stay as raw bytes until the handler reads a property or
 * {@code rawContent()}. That keeps a hello handler from paying for header strings, a query
 * parse, a form parse, or a cookie parse.
 */
public final class BlitzHttpRequest {

    final String method;
    final String target;
    final String path;
    final String query;
    final String version;
    final byte[] headerBlock;
    final byte[] body;
    final String remoteAddr;
    final int remotePort;
    final String localAddr;
    final int localPort;
    final long connectionId;
    final long requestTimeMillis;
    final long masterTimeMillis;
    final boolean keepAlive;
    final boolean head;
    final String scriptFilename;
    final String documentRoot;

    private Map<String, String> headers;
    private PhpArray get;
    private PhpArray post;
    private PhpArray cookie;
    private PhpArray files;
    private PhpArray server;
    private String bodyString;

    BlitzHttpRequest(String method, String target, String path, String query, String version,
                     byte[] headerBlock, byte[] body, String remoteAddr, int remotePort,
                     String localAddr, int localPort, long connectionId, long requestTimeMillis,
                     long masterTimeMillis, boolean keepAlive, boolean head,
                     String scriptFilename, String documentRoot) {
        this.method = method;
        this.target = target;
        this.path = path;
        this.query = query;
        this.version = version;
        this.headerBlock = headerBlock;
        this.body = body;
        this.remoteAddr = remoteAddr;
        this.remotePort = remotePort;
        this.localAddr = localAddr;
        this.localPort = localPort;
        this.connectionId = connectionId;
        this.requestTimeMillis = requestTimeMillis;
        this.masterTimeMillis = masterTimeMillis;
        this.keepAlive = keepAlive;
        this.head = head;
        this.scriptFilename = scriptFilename;
        this.documentRoot = documentRoot;
    }

    public String method() {
        return method;
    }

    public String target() {
        return target;
    }

    public String path() {
        return path;
    }

    public String query() {
        return query;
    }

    public String version() {
        return version;
    }

    public byte[] rawBody() {
        return body;
    }

    public long requestTimeMillis() {
        return requestTimeMillis;
    }

    public boolean keepAlive() {
        return keepAlive;
    }

    public boolean head() {
        return head;
    }

    public String remoteAddr() {
        return remoteAddr;
    }

    public int remotePort() {
        return remotePort;
    }

    public String localAddr() {
        return localAddr;
    }

    public int localPort() {
        return localPort;
    }

    public long connectionId() {
        return connectionId;
    }

    /** The body as a PHP byte string; the conversion happens on first read. */
    public String bodyText() {
        if (bodyString == null) {
            bodyString = body == null || body.length == 0 ? "" : PhpStrings.of(body);
        }
        return bodyString;
    }

    /** Request headers, lowercase names as Swoole reports them. Built on first read. */
    public Map<String, String> headers() {
        if (headers == null) {
            Map<String, String> map = new LinkedHashMap<>();
            if (headerBlock != null) {
                int i = 0;
                while (i < headerBlock.length) {
                    int lf = indexOf(headerBlock, (byte) '\n', i);
                    if (lf < 0) {
                        break;
                    }
                    int end = lf > i && headerBlock[lf - 1] == '\r' ? lf - 1 : lf;
                    if (end == i) {
                        break;
                    }
                    int colon = indexOf(headerBlock, (byte) ':', i);
                    if (colon < 0 || colon > end) {
                        i = lf + 1;
                        continue;
                    }
                    String name = lowerAscii(headerBlock, i, colon);
                    int valueStart = colon + 1;
                    while (valueStart < end && (headerBlock[valueStart] == ' ' || headerBlock[valueStart] == '\t')) {
                        valueStart++;
                    }
                    int valueEnd = end;
                    while (valueEnd > valueStart
                            && (headerBlock[valueEnd - 1] == ' ' || headerBlock[valueEnd - 1] == '\t')) {
                        valueEnd--;
                    }
                    String value = PhpStrings.of(headerBlock, valueStart, valueEnd - valueStart);
                    map.merge(name, value, (a, b) -> a + ", " + b);
                    i = lf + 1;
                }
            }
            headers = map;
        }
        return headers;
    }

    /** {@code $request->get}: the query string parsed when the property is first read. */
    public PhpArray getArray() {
        if (get == null) {
            get = PhpQuery.parseQuery(query);
        }
        return get;
    }

    /** {@code $request->post}: a urlencoded body, parsed when the property is first read. */
    public PhpArray postArray() {
        if (post == null) {
            String type = headers().get("content-type");
            if (body != null && body.length > 0 && type != null
                    && PhpStrings.lower(type).startsWith("application/x-www-form-urlencoded")) {
                post = PhpQuery.parseQuery(bodyText());
            } else {
                post = new PhpArray();
            }
        }
        return post;
    }

    public PhpArray cookieArray() {
        if (cookie == null) {
            cookie = PhpQuery.parseCookies(headers().get("cookie"));
        }
        return cookie;
    }

    /** Multipart uploads are not parsed yet; the property exists with the Swoole shape. */
    public PhpArray filesArray() {
        if (files == null) {
            files = new PhpArray();
        }
        return files;
    }

    /** {@code $request->server}, Swoole's lowercase keys. Built on first read. */
    public PhpArray serverArray() {
        if (server == null) {
            PhpArray out = PhpArray.withCapacity(24 + headers().size(), true);
            out.put("request_method", method);
            out.put("request_uri", target);
            out.put("path_info", path);
            out.put("query_string", query);
            out.put("request_time", requestTimeMillis / 1000);
            out.put("request_time_float", requestTimeMillis / 1000.0);
            out.put("server_protocol", version);
            out.put("server_port", (long) localPort);
            out.put("remote_port", (long) remotePort);
            out.put("remote_addr", remoteAddr);
            out.put("master_time", masterTimeMillis / 1000);
            out.put("server_software", "blitz");
            out.put("server_name", headers().getOrDefault("host", localAddr));
            out.put("document_root", documentRoot);
            out.put("script_filename", scriptFilename);
            out.put("script_name", scriptName());
            for (Map.Entry<String, String> e : headers().entrySet()) {
                String key = phpServerHeaderName(e.getKey());
                out.put(key, e.getValue());
            }
            // FrankenPHP/CGI spell these without the HTTP_ prefix; Swoole keeps both views.
            String contentType = headers().get("content-type");
            String contentLength = headers().get("content-length");
            out.put("content_type", contentType == null ? "" : contentType);
            out.put("content_length", contentLength == null ? "" : contentLength);
            server = out;
        }
        return server;
    }

    /** Drops the request's native caches once its handler returns. */
    void release() {
        headers = null;
        get = null;
        post = null;
        cookie = null;
        files = null;
        server = null;
        bodyString = null;
    }

    private String scriptName() {
        if (scriptFilename == null || scriptFilename.isEmpty()) {
            return "/";
        }
        int slash = Math.max(scriptFilename.lastIndexOf('/'), scriptFilename.lastIndexOf('\\'));
        return "/" + scriptFilename.substring(slash + 1);
    }

    private static String phpServerHeaderName(String name) {
        StringBuilder sb = new StringBuilder(name.length() + 5);
        sb.append("http_");
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append(c == '-' ? '_' : c);
        }
        return sb.toString();
    }

    static int indexOf(byte[] bytes, byte value, int from) {
        for (int i = from; i < bytes.length; i++) {
            if (bytes[i] == value) {
                return i;
            }
        }
        return -1;
    }

    static String lowerAscii(byte[] bytes, int from, int to) {
        char[] chars = new char[to - from];
        for (int i = from; i < to; i++) {
            byte b = bytes[i];
            chars[i - from] = b >= 'A' && b <= 'Z' ? (char) (b + ('a' - 'A')) : (char) (b & 0xff);
        }
        return new String(chars);
    }
}
