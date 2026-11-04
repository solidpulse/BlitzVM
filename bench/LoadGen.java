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
 * </pre>
 */
public class LoadGen {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "load" -> load(args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]));
            case "probe" -> probe(args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]));
            case "once" -> once(args[1]);
            default -> throw new IllegalArgumentException(args[0]);
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
        System.out.printf("requests=%d errors=%d rps=%.0f p50=%.2fms p99=%.2fms max=%.2fms%n",
                n, errors.get(), n / elapsed, pct(all, 50), pct(all, 99), n == 0 ? 0 : all.get(n - 1) / 1e6);
    }

    private static void run(String url, int concurrency, long end, List<List<Long>> sink, AtomicLong errors) throws Exception {
        URI uri = URI.create(url);
        String host = uri.getHost();
        int port = uri.getPort() < 0 ? 80 : uri.getPort();
        String target = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        byte[] request = ("GET " + target + " HTTP/1.1\r\nHost: " + host + ":" + port + "\r\nUser-Agent: loadgen\r\n\r\n")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
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
                while (System.nanoTime() < end) {
                    long t = System.nanoTime();
                    try {
                        if (conn == null) {
                            conn = new RawConnection(host, port);
                        }
                        int status = conn.exchange(request);
                        if (status != 200) {
                            errors.incrementAndGet();
                        }
                        if (conn.closed) {
                            conn = null;
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        conn = null;
                    }
                    lat.add(System.nanoTime() - t);
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

        RawConnection(String host, int port) throws java.io.IOException {
            socket = new java.net.Socket(host, port);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(60_000);
            out = socket.getOutputStream();
            in = new java.io.BufferedInputStream(socket.getInputStream(), 16384);
        }

        int exchange(byte[] request) throws java.io.IOException {
            out.write(request);
            out.flush();
            String statusLine = line();
            int status = Integer.parseInt(statusLine.split(" ")[1]);
            long length = -1;
            boolean chunked = false;
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
