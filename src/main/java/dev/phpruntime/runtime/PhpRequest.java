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

    public String method = "GET";
    public String uri = "/";
    public String queryString = "";
    public String scriptFilename = "";
    public String remoteAddr = "";
    public String body = "";
    public final Map<String, String> headers = new LinkedHashMap<>();
    public final Map<String, String> get = new LinkedHashMap<>();
    public final Map<String, String> post = new LinkedHashMap<>();
    public final Map<String, String> server = new LinkedHashMap<>();

    /** True when serving HTTP (a JS module's exported handler is then called with req/res). */
    public boolean http;

    public int status = 200;
    public final List<String[]> responseHeaders = new ArrayList<>();

    /** Scheduler hooks for this request; a no-op scheduler when run outside the server. */
    public GuestScheduler scheduler = GuestScheduler.NONE;

    public static PhpRequest current() {
        PhpRequest r = CURRENT.get();
        return r != null ? r : new PhpRequest();
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
