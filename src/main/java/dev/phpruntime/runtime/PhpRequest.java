package dev.phpruntime.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridge between the host (HTTP server, CLI) and the guest. The host fills in the
 * request fields and installs the object on the executing thread; guest builtins
 * such as header() and http_response_code() write the response fields.
 */
public final class PhpRequest {

    private static final ThreadLocal<PhpRequest> CURRENT = new ThreadLocal<>();

    /**
     * Host hook that appends this request's CGI variables to {@code $_SERVER}, after the
     * worker's environment entries. Worker mode installs it so a request that never reads
     * {@code $_SERVER} does not build its map or its strings.
     */
    @FunctionalInterface
    public interface ServerFiller {
        void fillServer(PhpRequest request, PhpArray server);
    }

    /** HTTP protocol data below is PHP bytes; scriptFilename alone is a Java path. */
    public String method = "GET";
    public String uri = "/";
    public String queryString = "";
    public String scriptFilename = "";
    public String remoteAddr = "";
    public String body = "";
    /** The request's HTTP version and local endpoint, as the CGI variables report them. */
    public String protocol = "HTTP/1.1";
    public String serverHost = "localhost";
    public int serverPort;
    /** PHP byte strings; file names outside these guest-facing fields remain Java paths. */
    public String[] argv = new String[0];
    /**
     * Header names keep their original case. The transport's per-request map is used directly
     * in worker mode; nothing on the request path mutates it afterwards.
     */
    public Map<String, String> headers;
    public final Map<String, String> get;
    public final Map<String, String> post;
    public final Map<String, String> server;
    /** Java OS environment text supplied by an embedder; empty by default, like php-cli. */
    public final Map<String, String> environment;
    /** Builds {@code $_SERVER} on first access in worker mode; null otherwise. */
    public ServerFiller serverFiller;

    public PhpRequest() {
        this(false);
    }

    /**
     * A worker request needs none of the Java request maps: the guest sees superglobals built
     * by {@code PhpContext}, and the CGI values come from {@link #serverFiller}. Sharing the
     * immutable empty maps keeps five empty maps out of every hello-style request.
     */
    public static PhpRequest worker() {
        return new PhpRequest(true);
    }

    private PhpRequest(boolean minimal) {
        if (minimal) {
            headers = Map.of();
            get = Map.of();
            post = Map.of();
            server = Map.of();
            environment = Map.of();
        } else {
            headers = new LinkedHashMap<>();
            get = new LinkedHashMap<>();
            post = new LinkedHashMap<>();
            server = new LinkedHashMap<>();
            environment = new LinkedHashMap<>();
        }
    }

    /** True when serving HTTP (a JS module's exported handler is then called with req/res). */
    public boolean http;

    public int status = 200;
    public final List<String[]> responseHeaders = new ArrayList<>();

    /** json_last_error() for this request. */
    public int jsonLastError;

    /** Host resources (database connections) the script left open; closed when the request ends. */
    public final List<AutoCloseable> resources = new ArrayList<>();

    /** Scheduler hooks for this request; a no-op scheduler when run outside the server. */
    public GuestScheduler scheduler = GuestScheduler.NONE;

    public static PhpRequest current() {
        PhpRequest r = CURRENT.get();
        return r != null ? r : new PhpRequest();
    }

    /** Closes whatever the script left open. Called by the host after the script finishes. */
    public void closeResources() {
        for (AutoCloseable r : resources) {
            try {
                r.close();
            } catch (Exception ignored) {
                // the request is over; nothing useful to report
            }
        }
        resources.clear();
    }

    public void install() {
        CURRENT.set(this);
    }

    public static void uninstall() {
        CURRENT.remove();
    }

    /**
     * Adds a response header. PHP's header() and setcookie() do their own checks and warn
     * first; this is the last guard for every language (JavaScript handlers included), so a
     * guest can never split a response even though the server writes headers unvalidated.
     */
    public void setHeader(String name, String value, boolean replace) {
        if (hasLineBreakOrNul(name) || hasLineBreakOrNul(value)) {
            throw new IllegalArgumentException("header name and value may not contain CR, LF or NUL");
        }
        if (replace) {
            responseHeaders.removeIf(h -> h[0].equalsIgnoreCase(name));
        }
        responseHeaders.add(new String[]{name, value});
    }

    private static boolean hasLineBreakOrNul(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r' || c == '\n' || c == '\0') {
                return true;
            }
        }
        return false;
    }
}
