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

    /** HTTP protocol data below is PHP bytes; scriptFilename alone is a Java path. */
    public String method = "GET";
    public String uri = "/";
    public String queryString = "";
    public String scriptFilename = "";
    public String remoteAddr = "";
    public String body = "";
    /** PHP byte strings; file names outside these guest-facing fields remain Java paths. */
    public String[] argv = new String[0];
    public final Map<String, String> headers = new LinkedHashMap<>();
    public final Map<String, String> get = new LinkedHashMap<>();
    public final Map<String, String> post = new LinkedHashMap<>();
    public final Map<String, String> server = new LinkedHashMap<>();
    /** Java OS environment text supplied by an embedder; empty by default, like php-cli. */
    public final Map<String, String> environment = new LinkedHashMap<>();

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

    public void setHeader(String name, String value, boolean replace) {
        if (replace) {
            responseHeaders.removeIf(h -> h[0].equalsIgnoreCase(name));
        }
        responseHeaders.add(new String[]{name, value});
    }
}
