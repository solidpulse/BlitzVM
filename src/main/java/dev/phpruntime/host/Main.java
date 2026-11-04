package dev.phpruntime.host;

import dev.phpruntime.runtime.PhpRequest;
import dev.phpruntime.sched.Scheduler;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command line entry point.
 *
 * <pre>
 *   php-truffle script.php                     run a script, output to stdout
 *   php-truffle serve [options] [docroot]      serve docroot over HTTP
 *     --port N         listen port (default 8080)
 *     --cpus N         concurrent PHP execution slots (default: available processors)
 *     --quantum-ms N   time slice before a busy request is preempted (default 10)
 *     --max-exec-ms N  per-request CPU budget, 0 = unlimited (default 30000)
 * </pre>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(64);
        }
        if (args[0].equals("serve")) {
            serve(args);
            return;
        }
        Path script = Path.of(args[0]);
        if (!Files.isRegularFile(script)) {
            System.err.println("Could not open input file: " + args[0]);
            System.exit(1);
        }
        PhpRequest request = new PhpRequest();
        request.uri = script.toString();
        request.server.put("SCRIPT_FILENAME", script.toAbsolutePath().toString());
        request.server.put("argc", Integer.toString(args.length));
        try (PhpEngine engine = new PhpEngine(null)) {
            int code = engine.executeStreaming(script, request, System.out, System.err);
            System.out.flush();
            System.exit(code);
        }
    }

    private static void serve(String[] args) throws Exception {
        int port = 8080;
        int cpus = Runtime.getRuntime().availableProcessors();
        long quantum = 10;
        long maxExec = 30_000;
        Path docRoot = Path.of(".");
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--cpus" -> cpus = Integer.parseInt(args[++i]);
                case "--quantum-ms" -> quantum = Long.parseLong(args[++i]);
                case "--max-exec-ms" -> maxExec = Long.parseLong(args[++i]);
                default -> docRoot = Path.of(args[i]);
            }
        }
        Scheduler scheduler = new Scheduler(cpus, quantum, maxExec);
        PhpEngine engine = new PhpEngine(scheduler);
        PhpHttpServer server = new PhpHttpServer(docRoot, port, engine, 256);
        server.start();
        System.out.printf("php-truffle serving %s on http://localhost:%d (cpus=%d, quantum=%dms, max-exec=%dms)%n",
                docRoot.toAbsolutePath().normalize(), server.port(), cpus, quantum, maxExec);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            engine.close();
            scheduler.close();
        }));
        Thread.currentThread().join();
    }

    private static void usage() {
        System.err.println("usage: php-truffle <script.php> | php-truffle serve [--port N] [--cpus N] [--quantum-ms N] [--max-exec-ms N] [docroot]");
    }
}
