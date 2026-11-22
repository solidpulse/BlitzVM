package dev.phpruntime.host;

import dev.phpruntime.runtime.PhpRequest;
import dev.phpruntime.runtime.PhpStrings;
import dev.phpruntime.sched.Scheduler;

import java.io.IOException;
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
 *     --profile <name|file>  serve eligible requests from boot snapshots (wordpress, or a
 *                       properties file); off without it
 *     --worker <script>     FrankenPHP-style worker mode: boot script once and keep it warm
 *     --workers N       worker threads (default: the --cpus count); may exceed --cpus
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
        if (args[0].equals("--engine-options")) {
            try (org.graalvm.polyglot.Engine engine = org.graalvm.polyglot.Engine.newBuilder("php")
                    .allowExperimentalOptions(true).build()) {
                System.out.printf("%s %s; supportsCompilation=%s%n", engine.getImplementationName(),
                        engine.getVersion(), org.graalvm.polyglot.Engine.supportsCompilation());
                com.oracle.truffle.api.TruffleRuntime runtime = com.oracle.truffle.api.Truffle.getRuntime();
                System.out.printf("TruffleRuntime=%s; name=%s%n", runtime.getClass().getName(), runtime.getName());
                java.util.stream.StreamSupport.stream(engine.getOptions().spliterator(), false)
                        .sorted(java.util.Comparator.comparing(org.graalvm.options.OptionDescriptor::getName))
                        .forEach(option -> System.out.printf("%s default=%s experimental=%s: %s%n",
                                option.getName(), option.getKey().getDefaultValue(),
                                option.getStability() == org.graalvm.options.OptionStability.EXPERIMENTAL,
                                option.getHelp()));
            }
            return;
        }
        if (args[0].equals("serve")) {
            serve(args);
            return;
        }
        if (args[0].equals("--snapshot-check")) {
            if (args.length != 3) {
                usage();
                System.exit(64);
            }
            System.exit(dev.phpruntime.snapshot.SnapshotCheck.runCli(
                    Path.of(args[1]), Path.of(args[2])));
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
        String profileName = null;
        Path workerScript = null;
        int workerCount = -1;
        long warmupSeconds = 0;
        java.util.List<String> warmupPaths = new java.util.ArrayList<>();
        String warmupHost = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--warmup-seconds" -> warmupSeconds = Long.parseLong(args[++i]);
                case "--warmup-path" -> warmupPaths.add(args[++i]);
                case "--warmup-host" -> warmupHost = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--cpus" -> cpus = Integer.parseInt(args[++i]);
                case "--quantum-ms" -> quantum = Long.parseLong(args[++i]);
                case "--max-exec-ms" -> maxExec = Long.parseLong(args[++i]);
                case "--http" -> http = args[++i];
                case "--profile" -> profileName = args[++i];
                case "--worker" -> workerScript = Path.of(args[++i]);
                case "--workers" -> workerCount = Integer.parseInt(args[++i]);
                default -> docRoot = Path.of(args[i]);
            }
        }
        if (workerScript != null && profileName != null) {
            System.err.println("php-truffle: --worker cannot be combined with --profile");
            System.exit(64);
            return;
        }
        if (workerScript != null && !Files.isRegularFile(workerScript)) {
            System.err.println("php-truffle: worker script not found: " + workerScript);
            System.exit(64);
            return;
        }
        SnapshotProfile profile = null;
        if (profileName != null) {
            try {
                profile = SnapshotProfile.load(profileName);
            } catch (RuntimeException | IOException e) {
                System.err.println("php-truffle: " + e.getMessage());
                System.exit(64);
            }
        }
        Scheduler scheduler = new Scheduler(cpus, quantum, maxExec);
        PhpEngine engine = new PhpEngine(scheduler);
        WorkerPool workers = null;
        PhpDispatcher dispatcher;
        try {
            if (workerScript != null) {
                workers = new WorkerPool(engine, scheduler, workerScript, docRoot,
                        workerCount > 0 ? workerCount : cpus, port);
                dispatcher = new PhpDispatcher(docRoot, engine, profile, workers);
            } else {
                dispatcher = new PhpDispatcher(docRoot, engine, profile);
            }
        } catch (IllegalArgumentException e) {
            System.err.println("php-truffle: " + e.getMessage());
            System.exit(64);
            return;
        }
        PhpServer server = http.equals("jdk")
                ? new PhpHttpServer(dispatcher, port, 256)
                : new NettyPhpServer(dispatcher, port, 256);
        String transport = server instanceof NettyPhpServer n ? n.transport() : "jdk-httpserver";
        if (workers != null) {
            int count = workers.workerCount();
            System.out.println("php-truffle worker mode: " + count + " worker" + (count == 1 ? "" : "s")
                    + " running " + workerScript.toAbsolutePath().normalize());
            workers.start();
            try {
                if (!workers.awaitReady(WorkerPool.STARTUP_TIMEOUT_MILLIS)) {
                    System.err.println("php-truffle: only " + workers.readyCount() + " of " + count
                            + " workers reached frankenphp_handle_request() within "
                            + WorkerPool.STARTUP_TIMEOUT_MILLIS / 1000 + " s; opening the port anyway");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (warmupSeconds > 0) {
            // Before the port opens: clients never see the interpreter-speed first minutes.
            java.util.List<String> paths = warmupPaths.isEmpty() ? java.util.List.of("/") : warmupPaths;
            String host = warmupHost != null ? warmupHost : "localhost:" + port;
            System.out.println("php-truffle warming up " + paths + " as " + host + " for " + warmupSeconds + " s");
            System.out.println("php-truffle warm-up: "
                    + Warmup.run(dispatcher, paths, host, port, cpus, warmupSeconds));
        }
        server.start();
        System.out.printf("php-truffle serving %s on http://localhost:%d (%s, cpus=%d, quantum=%dms, max-exec=%dms)%n",
                docRoot.toAbsolutePath().normalize(), server.port(), transport, cpus, quantum, maxExec);
        if (workers != null) {
            System.out.println("blitz worker mode: " + workers.workerCount() + " worker"
                    + (workers.workerCount() == 1 ? "" : "s") + ", script "
                    + workerScript.toAbsolutePath().normalize());
        }
        if (profile != null) {
            System.out.println("blitz snapshot: profile " + profile.name() + " " + profile.describe());
        }
        final WorkerPool workerPool = workers;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            if (workerPool != null) {
                workerPool.close();
            }
            dispatcher.close();
            engine.close();
            scheduler.close();
        }));
        Thread.currentThread().join();
    }

    private static void usage() {
        System.err.println("usage: php-truffle <script.php> | php-truffle serve [--port N] [--cpus N] [--quantum-ms N] [--max-exec-ms N] [--http netty|jdk] [--profile <name|file>] [--worker <script> [--workers N]]\n"
                + "         [--warmup-seconds N [--warmup-path /p ...] [--warmup-host host:port]] [docroot]");
        System.err.println("       php-truffle --snapshot-check <boot.php> <entry.php>   capture, restore twice and compare");
        System.err.println("       php-truffle --engine-options   list runtime and engine option descriptors");
    }
}
