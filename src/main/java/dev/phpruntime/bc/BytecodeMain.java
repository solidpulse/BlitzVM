package dev.phpruntime.bc;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;

import java.nio.file.Path;

/** Run with bench/bytecode/run-bc.sh file.php (after mvn package). */
public final class BytecodeMain {
    private BytecodeMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: BytecodeMain file.php");
        }
        try (Context context = Context.newBuilder(BytecodeLanguage.ID).build()) {
            context.eval(Source.newBuilder(BytecodeLanguage.ID, Path.of(args[0]).toFile()).build());
        }
    }
}
