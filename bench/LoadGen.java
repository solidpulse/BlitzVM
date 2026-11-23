import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal closed-loop HTTP load generator (no wrk/ab needed). Load mode uses one
 * blocking keep-alive socket per connection and parses responses itself: the JDK
 * HttpClient funnels all I/O through one selector thread and becomes the bottleneck
 * above a few thousand requests per second.
 *
 * <pre>
 *   java bench/LoadGen.java load  URL CONCURRENCY SECONDS   throughput and latency percentiles
 *   java bench/LoadGen.java probe URL COUNT GAP_MS          sequential requests, one latency line each
 *   java bench/LoadGen.java once  URL                       one request, prints status, time and body
 *   java -Dwarmup=0 bench/LoadGen.java curve URL CONCURRENCY WINDOW_SECONDS SAMPLES
 * </pre>
 */
public class LoadGen {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public static void main(String[] args) throws Exception {
        if (!args[0].equals("once") && !Boolean.getBoolean("skipPreflight")) {
            preflight(args[1]);
        }
        switch (args[0]) {
            case "load" -> load(args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]));
            case "probe" -> probe(args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]));
            case "once" -> once(args[1]);
            case "curve" -> curve(args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]));
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    /** Consecutive windows without a new Java launcher between samples. */
    private static void curve(String url, int concurrency, int windowSeconds, int samples) throws Exception {
        long start = System.nanoTime();
        for (int sample = 1; sample <= samples; sample++) {
            String machineLoad = java.nio.file.Files.readString(java.nio.file.Path.of("/proc/loadavg")).strip();
            System.out.printf("sample=%d start=%.3fs epoch_ms=%d load=%s ", sample,
                    (System.nanoTime() - start) / 1e9, System.currentTimeMillis(), machineLoad);
            load(url, concurrency, windowSeconds);
            System.out.flush();
        }
    }

    private static HttpResponse<String> get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static void once(String url) throws Exception {
        long t = System.nanoTime();
        HttpResponse<String> r = get(url);
        System.out.printf("%d %.1fms %s%n", r.statusCode(), (System.nanoTime() - t) / 1e6, r.body().strip());
    }

    private static void probe(String url, int count, int gapMs) throws Exception {
        for (int i = 1; i <= count; i++) {
            long t = System.nanoTime();
            HttpResponse<String> r = get(url);
            System.out.printf("probe %d: %d in %.1f ms%n", i, r.statusCode(), (System.nanoTime() - t) / 1e6);
            Thread.sleep(gapMs);
        }
    }

    /**
     * One request before any load: a run against a URL that answers 404, 500 or a redirect measures
     * nothing, so refuse to start (-DskipPreflight=true to bypass, -Dexpect=TEXT to also require
     * TEXT in the body).
     */
    private static void preflight(String url) throws Exception {
        // Sent like the load phase (raw connection, same -Dhost Host header), so a site that
        // redirects unless it sees its configured host passes here exactly when the run would.
        URI uri = URI.create(url);
        int port = uri.getPort() < 0 ? 80 : uri.getPort();
        String target = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        String hostHeader = System.getProperty("host", uri.getHost() + ":" + port);
        byte[] request = ("GET " + target + " HTTP/1.1\r\nHost: " + hostHeader
                + "\r\nUser-Agent: loadgen\r\nConnection: close\r\n\r\n")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int status;
        String text;
        RawConnection conn = null;
        try {
            conn = new RawConnection(uri.getHost(), port);
            conn.body = new java.io.ByteArrayOutputStream();
            status = conn.exchange(request);
            text = conn.body.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("PREFLIGHT FAILED " + url + ": " + e);
            System.exit(2);
            return;
        } finally {
            if (conn != null) { conn.close(); }
        }
        String expect = System.getProperty("expect");
        if (status != 200 || (expect != null && !text.contains(expect))) {
            String body = text.length() > 200 ? text.substring(0, 200) + "..." : text;
            System.out.println("PREFLIGHT FAILED " + url + ": status " + status + ", "
                    + text.length() + " bytes: " + body.replace('\n', ' '));
            System.exit(2);
        }
    }

    private static void load(String url, int concurrency, int seconds) throws Exception {
        // Warm-up: lets JITs (Graal, opcache) settle before measuring.
        long warmEnd = System.nanoTime() + Duration.ofSeconds(Long.getLong("warmup", 5)).toNanos();
        run(url, concurrency, warmEnd, null, new AtomicLong());

        List<List<Long>> perThread = new ArrayList<>();
        AtomicLong errors = new AtomicLong();
        long start = System.nanoTime();
        long end = start + Duration.ofSeconds(seconds).toNanos();
        run(url, concurrency, end, perThread, errors);
        double elapsed = (System.nanoTime() - start) / 1e9;

        List<Long> all = new ArrayList<>();
        perThread.forEach(all::addAll);
        Collections.sort(all);
        int n = all.size();
        System.out.printf("requests=%d errors=%d rps=%.0f p50=%.2fms p99=%.2fms max=%.2fms%s%n",
                n, errors.get(), n / elapsed, pct(all, 50), pct(all, 99), n == 0 ? 0 : all.get(n - 1) / 1e6,
                errors.get() * 100 > Math.max(n, 1) ? "  INVALID: more than 1% errors" : "");
    }

    private static void run(String url, int concurrency, long end, List<List<Long>> sink, AtomicLong errors) throws Exception {
        URI uri = URI.create(url);
        String host = uri.getHost();
        int port = uri.getPort() < 0 ? 80 : uri.getPort();
        String target = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        // -Dhost overrides the Host header (WordPress renders and redirects by its configured site URL).
        String hostHeader = System.getProperty("host", host + ":" + port);
        String head = "GET " + target + " HTTP/1.1\r\nHost: " + hostHeader + "\r\nUser-Agent: loadgen\r\n";
        byte[] request = (head + "\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        // -Dcookies=true: each connection acts as one browser, sending back the cookies it was given
        // (otherwise an app with sessions creates a new session on every request).
        boolean cookies = Boolean.getBoolean("cookies");
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            List<Long> lat = new ArrayList<>();
            if (sink != null) {
                synchronized (sink) {
                    sink.add(lat);
                }
            }
            threads.add(Thread.ofPlatform().start(() -> {
                RawConnection conn = null;
                java.util.Map<String, String> jar = new java.util.LinkedHashMap<>();
                byte[] withCookies = request;
                try {
                    while (System.nanoTime() < end) {
                        long t = System.nanoTime();
                        try {
                            if (conn == null) {
                                conn = new RawConnection(host, port);
                            }
                            conn.jar = cookies ? jar : null;
                            int status = conn.exchange(withCookies);
                            if (cookies && conn.jarChanged) {
                                StringBuilder c = new StringBuilder(head).append("Cookie: ");
                                jar.forEach((k, v) -> c.append(k).append('=').append(v).append("; "));
                                c.setLength(c.length() - 2);
                                withCookies = c.append("\r\n\r\n").toString()
                                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                            }
                            if (status != 200) {
                                errors.incrementAndGet();
                            }
                            if (conn.closed) {
                                conn = null;
                            }
                        } catch (Exception e) {
                            errors.incrementAndGet();
                            if (conn != null) { conn.close(); }
                            conn = null;
                        }
                        lat.add(System.nanoTime() - t);
                    }
                } finally {
                    if (conn != null) { conn.close(); }
                }
            }));
        }
        for (Thread t : threads) {
            t.join();
        }
    }

    /** One keep-alive HTTP/1.1 connection; understands Content-Length and chunked bodies. */
    private static final class RawConnection {
        private final java.net.Socket socket;
        private final java.io.OutputStream out;
        private final java.io.BufferedInputStream in;
        boolean closed;
        /** When set, the response body is kept here instead of skipped. */
        java.io.ByteArrayOutputStream body;
        java.util.Map<String, String> jar;
        boolean jarChanged;

        RawConnection(String host, int port) throws java.io.IOException {
            socket = new java.net.Socket(host, port);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(60_000);
            out = socket.getOutputStream();
            in = new java.io.BufferedInputStream(socket.getInputStream(), 16384);
        }

        void close() {
            closed = true;
            try { socket.close(); } catch (java.io.IOException ignored) { }
        }

        int exchange(byte[] request) throws java.io.IOException {
            out.write(request);
            out.flush();
            String statusLine = line();
            int status = Integer.parseInt(statusLine.split(" ")[1]);
            long length = -1;
            boolean chunked = false;
            jarChanged = false;
            for (String h = line(); !h.isEmpty(); h = line()) {
                int c = h.indexOf(':');
                String name = h.substring(0, c).trim();
                String value = h.substring(c + 1).trim();
                if (name.equalsIgnoreCase("Content-Length")) {
                    length = Long.parseLong(value);
                } else if (name.equalsIgnoreCase("Transfer-Encoding") && value.equalsIgnoreCase("chunked")) {
                    chunked = true;
                } else if (name.equalsIgnoreCase("Connection") && value.equalsIgnoreCase("close")) {
                    closed = true;
                } else if (jar != null && name.equalsIgnoreCase("Set-Cookie")) {
                    String pair = value.split(";", 2)[0];
                    int eq = pair.indexOf('=');
                    if (eq > 0 && !pair.substring(eq + 1).equals(jar.put(pair.substring(0, eq), pair.substring(eq + 1)))) {
                        jarChanged = true;
                    }
                }
            }
            if (chunked) {
                for (long size = Long.parseLong(line().trim(), 16); size > 0; size = Long.parseLong(line().trim(), 16)) {
                    skip(size);
                    line();
                }
                line();
            } else if (length > 0) {
                skip(length);
            } else if (length < 0 && closed && body != null) {
                in.transferTo(body); // no length on a closing connection: the body runs to EOF
            }
            if (closed) {
                socket.close();
            }
            return status;
        }

        private String line() throws java.io.IOException {
            StringBuilder sb = new StringBuilder();
            for (int b = in.read(); b != '\n'; b = in.read()) {
                if (b < 0) {
                    throw new java.io.EOFException();
                }
                if (b != '\r') {
                    sb.append((char) b);
                }
            }
            return sb.toString();
        }

        private void skip(long n) throws java.io.IOException {
            if (body != null) {
                byte[] buf = new byte[8192];
                while (n > 0) {
                    int k = in.read(buf, 0, (int) Math.min(buf.length, n));
                    if (k < 0) {
                        throw new java.io.EOFException();
                    }
                    body.write(buf, 0, k);
                    n -= k;
                }
                return;
            }
            while (n > 0) {
                long k = in.skip(n);
                if (k <= 0) {
                    if (in.read() < 0) {
                        throw new java.io.EOFException();
                    }
                    k = 1;
                }
                n -= k;
            }
        }
    }

    private static double pct(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, idx)) / 1e6;
    }
}
