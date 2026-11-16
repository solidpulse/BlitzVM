package dev.phpruntime.host;

import dev.phpruntime.runtime.PhpRequest;
import dev.phpruntime.runtime.PhpStrings;
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
 *     --http netty|jdk HTTP front end (default netty: native epoll on Linux)
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
        if (args[0].equals("-r")) {
            if (args.length < 2) {
                usage();
                System.exit(64);
            }
            try (org.graalvm.polyglot.Context ctx = org.graalvm.polyglot.Context.newBuilder("php")
                    .allowIO(org.graalvm.polyglot.io.IOAccess.ALL).build()) {
                // PhpLanguage converts Java character sources exactly once.
                ctx.eval(org.graalvm.polyglot.Source.newBuilder("php", "<?php " + args[1], "Command line code").build());
            }
            return;
        }
        Path script = Path.of(args[0]);
        if (!Files.isRegularFile(script)) {
            System.err.println("Could not open input file: " + args[0]);
            System.exit(1);
        }
        PhpRequest request = new PhpRequest();
        request.uri = script.toString();
        request.scriptFilename = script.toAbsolutePath().normalize().toString();
        request.server.put("SCRIPT_FILENAME", PhpStrings.fromJava(script.toAbsolutePath().toString()));
        request.server.put("argc", Integer.toString(args.length));
        request.argv = java.util.Arrays.stream(args).map(PhpStrings::fromJava).toArray(String[]::new);
        int code;
        try (PhpEngine engine = new PhpEngine(null)) {
            code = engine.executeStreaming(script, request, System.out, System.err);
            System.out.flush();
        }
        // Exit after the engine closes, so engine-level statistics and logs are flushed.
        System.exit(code);
    }

    private static void serve(String[] args) throws Exception {
        int port = 8080;
        int cpus = Runtime.getRuntime().availableProcessors();
        long quantum = 10;
        long maxExec = 30_000;
        Path docRoot = Path.of(".");
        String http = "netty";
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--cpus" -> cpus = Integer.parseInt(args[++i]);
                case "--quantum-ms" -> quantum = Long.parseLong(args[++i]);
                case "--max-exec-ms" -> maxExec = Long.parseLong(args[++i]);
                case "--http" -> http = args[++i];
                default -> docRoot = Path.of(args[i]);
            }
        }
        Scheduler scheduler = new Scheduler(cpus, quantum, maxExec);
        PhpEngine engine = new PhpEngine(scheduler);
        PhpDispatcher dispatcher = new PhpDispatcher(docRoot, engine);
        PhpServer server = http.equals("jdk")
                ? new PhpHttpServer(dispatcher, port, 256)
                : new NettyPhpServer(dispatcher, port, 256);
        String transport = server instanceof NettyPhpServer n ? n.transport() : "jdk-httpserver";
        server.start();
        System.out.printf("php-truffle serving %s on http://localhost:%d (%s, cpus=%d, quantum=%dms, max-exec=%dms)%n",
                docRoot.toAbsolutePath().normalize(), server.port(), transport, cpus, quantum, maxExec);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            engine.close();
            scheduler.close();
        }));
        Thread.currentThread().join();
    }

    private static void usage() {
        System.err.println("usage: php-truffle <script.php> | php-truffle serve [--port N] [--cpus N] [--quantum-ms N] [--max-exec-ms N] [--http netty|jdk] [docroot]");
    }
}
