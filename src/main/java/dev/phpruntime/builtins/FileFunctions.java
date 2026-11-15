package dev.phpruntime.builtins;

import com.oracle.truffle.api.TruffleFile;
import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpOps;

import java.io.IOException;

/** Path and filesystem inspection builtins needed during boot. */
final class FileFunctions {

    private FileFunctions() {
    }

    static void register() {
        Builtins.def("dirname", 1, 2, (ctx, a) ->
                dirname(PhpOps.toStr(a[0]), a.length > 1 ? PhpOps.toLong(a[1]) : 1L));
        Builtins.def("basename", 1, 2, (ctx, a) ->
                basename(PhpOps.toStr(a[0]), a.length > 1 ? PhpOps.toStr(a[1]) : ""));
        Builtins.def("file_exists", 1, 1, (ctx, a) -> check(ctx, PhpOps.toStr(a[0]), TruffleFile::exists));
        Builtins.def("is_file", 1, 1, (ctx, a) -> check(ctx, PhpOps.toStr(a[0]), TruffleFile::isRegularFile));
        Builtins.def("is_dir", 1, 1, (ctx, a) -> check(ctx, PhpOps.toStr(a[0]), TruffleFile::isDirectory));
        Builtins.def("is_readable", 1, 1, (ctx, a) -> check(ctx, PhpOps.toStr(a[0]), f -> f.exists() && f.isReadable()));
        Builtins.def("realpath", 1, 1, (ctx, a) -> realpath(ctx, PhpOps.toStr(a[0])));
    }

    static String dirname(String path, long levels) {
        if (levels < 1) {
            throw new PhpError("dirname(): Argument #2 ($levels) must be greater than or equal to 1");
        }
        if (path.isEmpty()) {
            return "";
        }
        for (long i = 0; i < levels; i++) {
            int end = path.length();
            while (end > 0 && path.charAt(end - 1) == '/') {
                end--;
            }
            if (end == 0) {
                return "/";
            }
            int slash = path.lastIndexOf('/', end - 1);
            if (slash < 0) {
                return ".";
            }
            while (slash > 0 && path.charAt(slash - 1) == '/') {
                slash--;
            }
            path = slash == 0 ? "/" : path.substring(0, slash);
        }
        return path;
    }

    static String basename(String path, String suffix) {
        if (path.isEmpty()) {
            return "";
        }
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') {
            end--;
        }
        if (end == 0) {
            return "";
        }
        int slash = path.lastIndexOf('/', end - 1);
        String base = path.substring(slash + 1, end);
        if (!suffix.isEmpty() && base.length() > suffix.length() && base.endsWith(suffix)) {
            base = base.substring(0, base.length() - suffix.length());
        }
        return base;
    }

    @FunctionalInterface
    private interface FilePredicate {
        boolean test(TruffleFile file);
    }

    private static boolean check(PhpContext ctx, String path, FilePredicate pred) {
        if (path.isEmpty()) {
            return false;
        }
        try {
            return pred.test(ctx.env().getPublicTruffleFile(path));
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private static Object realpath(PhpContext ctx, String path) {
        try {
            TruffleFile f = path.isEmpty()
                    ? ctx.env().getCurrentWorkingDirectory()
                    : ctx.env().getPublicTruffleFile(path);
            if (!f.exists()) {
                return false;
            }
            return f.getCanonicalFile().getPath();
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
