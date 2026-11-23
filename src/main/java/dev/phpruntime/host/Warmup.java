package dev.phpruntime.host;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs requests through the dispatcher before the server accepts connections, so the first
 * real requests find compiled code instead of the interpreter. The requests take exactly the
 * path real ones do (routing, snapshots, the scheduler, the context pool); only the socket is
 * missing. Side effects are those of the same GET requests from a client.
 */
final class Warmup {

    private Warmup() {
    }

    /** Requests {@code paths} on {@code threads} threads until {@code seconds} have passed. */
    static String run(PhpDispatcher dispatcher, List<String> paths, String host, int port,
                      int threads, long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        AtomicLong done = new AtomicLong();
        Map<Integer, AtomicLong> statuses = new ConcurrentHashMap<>();
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            int offset = t;
            workers[t] = Thread.ofPlatform().name("blitz-warmup-" + t).stackSize(PhpThreads.STACK_BYTES).start(() -> {
                for (long i = offset; System.nanoTime() < deadline; i++) {
                    String path = paths.get((int) (i % paths.size()));
                    int q = path.indexOf('?');
                    PhpDispatcher.Request req = new PhpDispatcher.Request("GET",
                            q < 0 ? path : path.substring(0, q), q < 0 ? null : path.substring(q + 1),
                            "HTTP/1.1", Map.of("Host", host, "User-Agent", "blitz-warmup"), new byte[0],
                            "127.0.0.1", "localhost", port);
                    int status;
                    try {
                        status = dispatcher.handle(req).status();
                    } catch (RuntimeException e) {
                        status = -1;
                    }
                    statuses.computeIfAbsent(status, k -> new AtomicLong()).incrementAndGet();
                    done.incrementAndGet();
                }
            });
        }
        for (Thread w : workers) {
            w.join();
        }
        Map<Integer, Long> summary = new TreeMap<>();
        statuses.forEach((k, v) -> summary.put(k, v.get()));
        return done.get() + " requests in " + seconds + " s, statuses " + summary;
    }
}
