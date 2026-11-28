package dev.phpruntime.host;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Front end on the JDK's built-in {@code com.sun.net.httpserver}. Kept for comparison
 * ({@code --http jdk}); {@link NettyPhpServer} is the default and much faster.
 */
public final class PhpHttpServer implements PhpServer {

    private final PhpDispatcher dispatcher;
    private final HttpServer server;
    private final ExecutorService workers;

    public PhpHttpServer(PhpDispatcher dispatcher, int port, int maxConnections) throws IOException {
        this.dispatcher = dispatcher;
        this.server = HttpServer.create(new InetSocketAddress(port), 1024);
        this.workers = Executors.newFixedThreadPool(maxConnections,
                r -> PhpThreads.newThread(r, "php-http-worker", true));
        server.setExecutor(workers);
        server.createContext("/", this::handle);
    }

    @Override
    public void start() {
        server.start();
    }

    @Override
    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            Map<String, String> headers = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> h : ex.getRequestHeaders().entrySet()) {
                headers.put(h.getKey(), String.join(", ", h.getValue()));
            }
            byte[] body;
            try (InputStream in = ex.getRequestBody()) {
                body = in.readAllBytes();
            }
            InetSocketAddress local = ex.getLocalAddress();
            PhpDispatcher.Response res = dispatcher.handle(new PhpDispatcher.Request(
                    ex.getRequestMethod(), ex.getRequestURI().getRawPath(), ex.getRequestURI().getRawQuery(),
                    ex.getProtocol(), headers, body, ex.getRemoteAddress().getAddress().getHostAddress(),
                    local.getHostString(), local.getPort()));
            for (String[] h : res.headers()) {
                ex.getResponseHeaders().add(h[0], h[1]);
            }
            ex.sendResponseHeaders(res.status(), res.body().length == 0 ? -1 : res.body().length);
            if (res.body().length > 0) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(res.body());
                }
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
        workers.shutdownNow();
    }
}
