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
 * Minimal closed-loop HTTP load generator (no wrk/ab needed).
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
        long warmEnd = System.nanoTime() + Duration.ofSeconds(Math.min(5, seconds)).toNanos();
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
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            List<Long> lat = new ArrayList<>();
            if (sink != null) {
                synchronized (sink) {
                    sink.add(lat);
                }
            }
            threads.add(Thread.ofPlatform().start(() -> {
                while (System.nanoTime() < end) {
                    long t = System.nanoTime();
                    try {
                        HttpResponse<String> r = get(url);
                        if (r.statusCode() != 200) {
                            errors.incrementAndGet();
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                    lat.add(System.nanoTime() - t);
                }
            }));
        }
        for (Thread t : threads) {
            t.join();
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
