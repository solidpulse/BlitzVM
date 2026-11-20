package dev.phpruntime.snapshot;

import dev.phpruntime.runtime.PhpRequest;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.io.IOAccess;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Runs an entry script four ways: fully, in template mode (capture), and twice restored from
 * the snapshot. The two restored runs must produce the full run's output and boundary
 * fingerprint; the second runs after the first has mutated everything it could, which proves
 * the snapshot itself stayed untouched.
 */
public final class SnapshotCheck {

    /** One execution of the entry script. */
    private record Run(byte[] out, byte[] err, SnapshotRun snapshotRun, String failure) {
        String output() {
            return new String(out, StandardCharsets.ISO_8859_1);
        }

        String errors() {
            return new String(err, StandardCharsets.ISO_8859_1);
        }
    }

    /** The harness report. {@code ok} means output and fingerprints all agreed. */
    public record Result(boolean ok, boolean refused, String refusal, List<String> refusalReasons,
                         List<String> differences, String fullOutput, String firstOutput,
                         String secondOutput, String templateOutput,
                         Map<String, String> fullFingerprint,
                         Map<String, String> firstFingerprint,
                         Map<String, String> secondFingerprint, String error) {
    }

    private SnapshotCheck() {
    }

    public static Engine newEngine() {
        return Engine.newBuilder("php")
                .allowExperimentalOptions(true)
                .option("engine.WarnInterpreterOnly", "false")
                .build();
    }

    public static Result check(Path bootFile, Path entryFile) throws IOException {
        try (Engine engine = newEngine()) {
            return check(engine, bootFile, entryFile);
        }
    }

    public static Result check(Engine engine, Path bootFile, Path entryFile) throws IOException {
        Path boot = bootFile.toRealPath();
        Path entry = entryFile.toRealPath();
        String bootPath = boot.toString();

        Run full = run(engine, entry, SnapshotRun.full(bootPath));
        Run template = run(engine, entry, SnapshotRun.template(bootPath));
        if (!template.snapshotRun.bootReached()) {
            List<String> reasons = List.of("the boot include " + bootPath + " was never reached");
            return new Result(false, true, reasons.get(0), reasons, List.of(), full.output(),
                    "", "", template.output(), Map.of(), Map.of(), Map.of(), template.failure());
        }
        if (template.snapshotRun.refusal() != null) {
            List<String> reasons = template.snapshotRun.refusal();
            return new Result(false, true, reasons.get(0), reasons, List.of(), full.output(),
                    "", "", template.output(), Map.of(), Map.of(), Map.of(), template.failure());
        }
        Snapshot snapshot = template.snapshotRun.snapshot();
        Run first = run(engine, entry, SnapshotRun.restored(bootPath, snapshot));
        Run second = run(engine, entry, SnapshotRun.restored(bootPath, snapshot));

        List<String> differences = new ArrayList<>();
        if (full.failure() != null) {
            differences.add("full run failed: " + full.failure());
        }
        if (template.failure() != null) {
            differences.add("template run failed: " + template.failure());
        }
        if (first.failure() != null) {
            differences.add("first restore failed: " + first.failure());
        }
        if (second.failure() != null) {
            differences.add("second restore failed: " + second.failure());
        }
        if (!full.output().equals(first.output())) {
            differences.add("first restore output differs from the full run");
        }
        if (!full.output().equals(second.output())) {
            differences.add("second restore output differs from the full run");
        }
        compareFingerprints("first restore", full.snapshotRun.fingerprint(),
                first.snapshotRun.fingerprint(), differences);
        compareFingerprints("second restore", full.snapshotRun.fingerprint(),
                second.snapshotRun.fingerprint(), differences);

        boolean ok = differences.isEmpty();
        return new Result(ok, false, null, List.of(), differences, full.output(),
                first.output(), second.output(), template.output(),
                full.snapshotRun.fingerprint(), first.snapshotRun.fingerprint(),
                second.snapshotRun.fingerprint(), null);
    }

    private static void compareFingerprints(String label, Map<String, String> expected,
                                            Map<String, String> actual, List<String> differences) {
        if (expected == null || actual == null) {
            differences.add(label + " has no boundary fingerprint");
            return;
        }
        Set<String> names = new LinkedHashSet<>();
        names.addAll(expected.keySet());
        names.addAll(actual.keySet());
        List<String> different = new ArrayList<>();
        for (String name : names) {
            String a = expected.get(name);
            String b = actual.get(name);
            if (a == null) {
                different.add(name + " (missing in the full run)");
            } else if (b == null) {
                different.add(name + " (missing in the restored run)");
            } else if (!a.equals(b)) {
                different.add(name + " (full " + a + ", restored " + b + ")");
            }
        }
        if (!different.isEmpty()) {
            differences.add(label + " fingerprint differs at " + different.size() + " component"
                    + (different.size() == 1 ? "" : "s") + ": " + String.join(", ", different));
        }
    }

    private static Run run(Engine engine, Path entry, SnapshotRun snapshotRun) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PhpRequest request = request(entry);
        request.install();
        SnapshotSupport.setPending(snapshotRun);
        String failure = null;
        try (Context context = Context.newBuilder("php")
                .engine(engine)
                .allowIO(IOAccess.ALL)
                .out(out)
                .err(err)
                .build()) {
            context.eval(Source.newBuilder("php", entry.toFile())
                    .encoding(StandardCharsets.ISO_8859_1).build());
        } catch (PolyglotException e) {
            failure = (e.isInternalError() ? "internal error: " : "") + e.getMessage();
        } catch (IOException e) {
            failure = e.getMessage();
        } finally {
            SnapshotSupport.clearPending();
            request.closeResources();
            PhpRequest.uninstall();
        }
        return new Run(out.toByteArray(), err.toByteArray(), snapshotRun, failure);
    }

    /** A synthetic GET of the entry script, like the request a template run must use. */
    public static PhpRequest request(Path entry) {
        PhpRequest request = new PhpRequest();
        request.http = true;
        request.method = "GET";
        request.uri = "/";
        request.queryString = "";
        request.scriptFilename = entry.toString();
        request.remoteAddr = "127.0.0.1";
        // A harness mirrors php-cli: the process environment is visible to getenv().
        request.environment.putAll(System.getenv());
        String name = "/" + entry.getFileName();
        // WordPress's wp_fix_server_vars() merges these two in front of $_SERVER; putting them
        // first here makes that merge preserve both values and order, so the global is unchanged.
        request.server.put("SERVER_SOFTWARE", "BlitzVM");
        request.server.put("REQUEST_URI", "/");
        request.server.put("DOCUMENT_ROOT", entry.getParent().toString());
        request.server.put("SCRIPT_FILENAME", entry.toString());
        request.server.put("SCRIPT_NAME", name);
        request.server.put("PHP_SELF", name);
        request.server.put("REQUEST_METHOD", "GET");
        request.server.put("QUERY_STRING", "");
        request.server.put("SERVER_NAME", "localhost");
        request.server.put("SERVER_PORT", "18088");
        request.server.put("SERVER_PROTOCOL", "HTTP/1.1");
        request.server.put("HTTP_HOST", "localhost:18088");
        request.server.put("REMOTE_ADDR", "127.0.0.1");
        request.server.put("GATEWAY_INTERFACE", "CGI/1.1");
        return request;
    }

    /** {@code php-truffle --snapshot-check <boot> <entry>}; returns a process exit code. */
    public static int runCli(Path bootFile, Path entryFile) {
        Result result;
        try {
            result = check(bootFile, entryFile);
        } catch (IOException e) {
            System.out.println("snapshot-check: " + e.getMessage());
            return 2;
        }
        System.out.println("snapshot-check: boot " + bootFile + ", entry " + entryFile);
        if (result.error() != null) {
            System.out.println("run error: " + result.error());
        }
        if (result.refused()) {
            System.out.println("capture refused:");
            for (String reason : result.refusalReasons()) {
                System.out.println("  - " + reason);
            }
            return 1;
        }
        System.out.println("full run: " + result.fullOutput().length() + " output bytes, "
                + componentCount(result.fullFingerprint()) + " fingerprint components");
        System.out.println("template run: aborted at the boot boundary, "
                + result.templateOutput().length() + " output bytes before it");
        System.out.println("first restore: " + (result.fullOutput().equals(result.firstOutput())
                ? "output same" : "OUTPUT DIFFERS") + ", "
                + sameCount(result.fullFingerprint(), result.firstFingerprint()) + " components same");
        System.out.println("second restore: " + (result.fullOutput().equals(result.secondOutput())
                ? "output same" : "OUTPUT DIFFERS") + ", "
                + sameCount(result.fullFingerprint(), result.secondFingerprint()) + " components same");
        if (result.ok()) {
            System.out.println("RESULT: OK");
            return 0;
        }
        System.out.println("RESULT: FAILED");
        for (String difference : result.differences()) {
            System.out.println("  - " + difference);
        }
        return 1;
    }

    private static int componentCount(Map<String, String> fingerprint) {
        return fingerprint == null ? 0 : fingerprint.size();
    }

    private static int sameCount(Map<String, String> a, Map<String, String> b) {
        if (a == null || b == null) {
            return 0;
        }
        int same = 0;
        for (Map.Entry<String, String> entry : new TreeMap<>(a).entrySet()) {
            if (entry.getValue().equals(b.get(entry.getKey()))) {
                same++;
            }
        }
        return same;
    }
}
