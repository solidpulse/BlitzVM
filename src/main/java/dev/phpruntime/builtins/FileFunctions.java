package dev.phpruntime.builtins;

import com.oracle.truffle.api.TruffleFile;
import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpStrings;

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
        Builtins.def("pathinfo", 1, 2, (ctx, a) -> pathinfo(PhpOps.toStr(a[0]), a.length > 1 ? PhpOps.toLong(a[1]) : 15));
        io();
    }

    static String dirname(String path, long levels) {
        if (levels < 1) {
            throw new PhpError(PhpError.VALUE_ERROR, "dirname(): Argument #2 ($levels) must be greater than or equal to 1", null);
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
            return pred.test(ctx.env().getPublicTruffleFile(PhpStrings.toJava(path)));
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * php's realpath cache: resolutions of absolute paths, shared by all requests and reused for
     * a short time. Only paths that resolve are kept, and unlink, rmdir, rename and
     * clearstatcache(true) empty it, as in php. WordPress resolves every core block's directory
     * on each request, so without it realpath() was a few percent of all CPU time.
     */
    private record RealpathEntry(String resolved, long checkedAt) {
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, RealpathEntry> REALPATH_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long REALPATH_TTL_NANOS = Long.getLong("blitz.realpath.ttlMs", 2000L) * 1_000_000L;
    private static final int REALPATH_CACHE_LIMIT = 16384;

    static void clearRealpathCache() {
        REALPATH_CACHE.clear();
    }

    /**
     * php resolves a path one component at a time, so "a/missing/../b" fails even when "a/b"
     * exists; Java normalizes the ".." away first. Checks the directory before each "..".
     */
    private static boolean dotDotParentsExist(PhpContext ctx, String path) {
        if (!path.contains("..")) {
            return true;
        }
        String[] parts = path.split("/", -1);
        StringBuilder prefix = new StringBuilder(path.startsWith("/") ? "/" : "");
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (part.equals("..") && prefix.length() > 0 && !prefix.toString().equals("/")) {
                TruffleFile dir = ctx.env().getPublicTruffleFile(PhpStrings.toJava(prefix.toString()));
                if (!dir.isDirectory()) {
                    return false;
                }
            }
            if (prefix.length() > 0 && prefix.charAt(prefix.length() - 1) != '/') {
                prefix.append('/');
            }
            prefix.append(part);
        }
        return true;
    }

    private static Object realpath(PhpContext ctx, String path) {
        boolean cacheable = REALPATH_TTL_NANOS > 0 && !path.isEmpty() && path.charAt(0) == '/';
        if (cacheable) {
            RealpathEntry e = REALPATH_CACHE.get(path);
            if (e != null && System.nanoTime() - e.checkedAt() < REALPATH_TTL_NANOS) {
                return e.resolved();
            }
        }
        try {
            TruffleFile f = path.isEmpty()
                    ? ctx.env().getCurrentWorkingDirectory()
                    : ctx.env().getPublicTruffleFile(PhpStrings.toJava(path));
            if (!f.exists() || !dotDotParentsExist(ctx, path)) {
                return false;
            }
            String resolved = PhpStrings.fromJava(f.getCanonicalFile().getPath());
            if (cacheable) {
                if (REALPATH_CACHE.size() >= REALPATH_CACHE_LIMIT) {
                    REALPATH_CACHE.clear();
                }
                REALPATH_CACHE.put(path, new RealpathEntry(resolved, System.nanoTime()));
            }
            return resolved;
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /** pathinfo: all parts for PATHINFO_ALL (15), otherwise the first selected part or "". */
    private static Object pathinfo(String path, long opt) {
        dev.phpruntime.runtime.PhpArray out = new dev.phpruntime.runtime.PhpArray();
        if ((opt & 1) != 0) {
            String dir = dirname(path, 1);
            if (!dir.isEmpty()) {
                out.put("dirname", dir);
            }
        }
        String base = basename(path, "");
        if ((opt & 2) != 0) {
            out.put("basename", base);
        }
        int dot = base.lastIndexOf('.');
        if ((opt & 4) != 0 && dot >= 0) {
            out.put("extension", base.substring(dot + 1));
        }
        if ((opt & 8) != 0) {
            out.put("filename", dot >= 0 ? base.substring(0, dot) : base);
        }
        if (opt == 15) {
            return out;
        }
        java.util.List<Object> values = out.values();
        return values.isEmpty() ? "" : values.get(0);
    }

    // ------------------------------------------------------------ reading and writing files

    private static void io() {
        Builtins.def("file_get_contents", 1, 5, (ctx, a) -> {
            String path = PhpOps.toStr(a[0]);
            if (PhpStrings.lower(path).startsWith("php://")) {
                PhpStream stream = StreamFunctions.openStream(ctx, path, "rb", "file_get_contents");
                if (stream == null) {
                    return false;
                }
                try {
                    StreamFunctions.consumeStreamId(ctx);
                    long offset = a.length > 3 ? PhpOps.toLong(a[3]) : 0;
                    if (offset != 0 && !stream.seek(offset < 0 ? stream.size() + offset : offset)) {
                        return false;
                    }
                    return PhpStrings.of(a.length > 4 && a[4] != dev.phpruntime.runtime.PhpNull.INSTANCE
                            ? stream.readBytes((int) PhpOps.toLong(a[4])) : stream.readAll());
                } catch (IOException e) {
                    return false;
                } finally {
                    try {
                        stream.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            TruffleFile f = file(ctx, path);
            try {
                if (f == null || !f.isRegularFile()) {
                    throw new IOException();
                }
                byte[] data = f.readAllBytes();
                StreamFunctions.consumeStreamId(ctx);
                long requestedOffset = a.length > 3 ? PhpOps.toLong(a[3]) : 0;
                int offset = (int) Math.max(0, Math.min(data.length, requestedOffset < 0 ? data.length + requestedOffset : requestedOffset));
                int end = data.length;
                if (a.length > 4 && a[4] != dev.phpruntime.runtime.PhpNull.INSTANCE) {
                    end = (int) Math.min(data.length, offset + Math.max(0, PhpOps.toLong(a[4])));
                }
                if (offset >= end) {
                    return "";
                }
                return PhpStrings.of(data, offset, end - offset);
            } catch (IOException | SecurityException e) {
                ctx.warning("file_get_contents(" + path + "): Failed to open stream: No such file or directory", 0);
                return false;
            }
        });
        Builtins.def("file_put_contents", 2, 4, (ctx, a) -> {
            String path = PhpOps.toStr(a[0]);
            String data;
            if (a[1] instanceof dev.phpruntime.runtime.PhpArray arr) {
                StringBuilder sb = new StringBuilder();
                for (Object v : arr.values()) {
                    sb.append(PhpOps.toStr(v));
                }
                data = sb.toString();
            } else {
                data = PhpOps.toStr(a[1]);
            }
            boolean append = a.length > 2 && (PhpOps.toLong(a[2]) & 8) != 0;
            byte[] bytes = PhpStrings.bytes(data);
            if (PhpStrings.lower(path).startsWith("php://")) {
                PhpStream stream = StreamFunctions.openStream(ctx, path, append ? "ab" : "wb", "file_put_contents");
                if (stream == null || !stream.canWrite) {
                    return false;
                }
                try {
                    StreamFunctions.consumeStreamId(ctx);
                    stream.write(bytes);
                    return (long) bytes.length;
                } catch (IOException e) {
                    return false;
                } finally {
                    try {
                        stream.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            TruffleFile f = file(ctx, path);
            try {
                if (f == null) {
                    throw new IOException();
                }
                java.util.Set<java.nio.file.OpenOption> opts = append
                        ? java.util.Set.of(java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.APPEND)
                        : java.util.Set.of(java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                try (java.io.OutputStream out = f.newOutputStream(opts.toArray(new java.nio.file.OpenOption[0]))) {
                    out.write(bytes);
                }
                StreamFunctions.consumeStreamId(ctx);
                return (long) bytes.length;
            } catch (IOException | SecurityException e) {
                ctx.warning("file_put_contents(" + path + "): Failed to open stream: No such file or directory", 0);
                return false;
            }
        });
        Builtins.def("filesize", 1, 1, (ctx, a) -> stat(ctx, "filesize", PhpOps.toStr(a[0]), f -> f.size()));
        Builtins.def("filemtime", 1, 1, (ctx, a) -> stat(ctx, "filemtime", PhpOps.toStr(a[0]), f -> f.getLastModifiedTime().toMillis() / 1000));
        Builtins.def("is_writable", 1, 1, (ctx, a) -> check(ctx, PhpOps.toStr(a[0]), f -> f.exists() && f.isWritable()));
        Builtins.def("is_writeable", 1, 1, (ctx, a) -> check(ctx, PhpOps.toStr(a[0]), f -> f.exists() && f.isWritable()));
        Builtins.def("is_executable", 1, 1, (ctx, a) -> check(ctx, PhpOps.toStr(a[0]), f -> f.exists() && f.isExecutable()));
        Builtins.def("chdir", 1, 1, (ctx, a) -> {
            String path = PhpOps.toStr(a[0]);
            try {
                TruffleFile dir = ctx.env().getPublicTruffleFile(PhpStrings.toJava(path));
                if (!dir.isDirectory()) {
                    ctx.warning("chdir(): No such file or directory (errno 2)", 0);
                    return false;
                }
                ctx.env().setCurrentWorkingDirectory(dir);
                return true;
            } catch (SecurityException | IllegalArgumentException e) {
                ctx.warning("chdir(): No such file or directory (errno 2)", 0);
                return false;
            }
        });
        // Uploaded files do not exist in this runtime; php returns false for a path that
        // was not uploaded, which is exactly what a plain fopen'ed path is here.
        Builtins.def("is_uploaded_file", 1, 1, (ctx, a) -> false);
        Builtins.def("move_uploaded_file", 2, 2, (ctx, a) -> false);
        Builtins.def("mime_content_type", 1, 1, MimeFunctions::detect);
        Builtins.def("scandir", 1, 3, (ctx, a) -> {
            String path = PhpOps.toStr(a[0]);
            long order = a.length > 1 ? PhpOps.toLong(a[1]) : 0;
            TruffleFile dir = file(ctx, path);
            try {
                if (dir == null || !dir.isDirectory()) {
                    throw new IOException();
                }
                java.util.List<String> names = new java.util.ArrayList<>();
                names.add(".");
                names.add("..");
                for (TruffleFile child : dir.list()) {
                    names.add(PhpStrings.fromJava(child.getName()));
                }
                StreamFunctions.consumeStreamId(ctx);
                if (order == 0) {
                    names.sort(FileFunctions::compareBytes);
                } else if (order == 1) {
                    names.sort((x, y) -> compareBytes(y, x));
                }
                return dev.phpruntime.runtime.PhpArray.ofList(names);
            } catch (IOException | SecurityException e) {
                ctx.warning("scandir(" + path + "): Failed to open directory: No such file or directory", 0);
                ctx.warning("scandir(): (errno 2): No such file or directory", 0);
                return false;
            }
        });
        Builtins.def("mkdir", 1, 4, (ctx, a) -> {
            String path = PhpOps.toStr(a[0]);
            boolean recursive = a.length > 2 && PhpOps.toBool(a[2]);
            TruffleFile f = file(ctx, path);
            try {
                if (f == null) {
                    throw new IOException();
                }
                if (f.exists()) {
                    ctx.warning("mkdir(): File exists", 0);
                    return false;
                }
                if (recursive) {
                    f.createDirectories();
                } else {
                    f.createDirectory();
                }
                return true;
            } catch (IOException | SecurityException e) {
                ctx.warning("mkdir(): No such file or directory", 0);
                return false;
            }
        });
        Builtins.def("rmdir", 1, 2, (ctx, a) -> delete(ctx, "rmdir", PhpOps.toStr(a[0]), true));
        Builtins.def("unlink", 1, 2, (ctx, a) -> delete(ctx, "unlink", PhpOps.toStr(a[0]), false));
    }

    private static TruffleFile file(PhpContext ctx, String path) {
        if (path.isEmpty()) {
            return null;
        }
        try {
            return ctx.env().getPublicTruffleFile(PhpStrings.toJava(path));
        } catch (SecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    @FunctionalInterface
    private interface FileStat {
        long get(TruffleFile file) throws IOException;
    }

    private static Object stat(PhpContext ctx, String fn, String path, FileStat stat) {
        TruffleFile f = file(ctx, path);
        try {
            if (f == null || !f.exists()) {
                throw new IOException();
            }
            return stat.get(f);
        } catch (IOException | SecurityException e) {
            ctx.warning(fn + "(): stat failed for " + path, 0);
            return false;
        }
    }

    private static Object delete(PhpContext ctx, String fn, String path, boolean directory) {
        clearRealpathCache();
        TruffleFile f = file(ctx, path);
        try {
            if (f == null || !f.exists()) {
                ctx.warning(fn + "(" + path + "): No such file or directory", 0);
                return false;
            }
            if (f.isDirectory() != directory) {
                ctx.warning(fn + "(" + path + "): " + (directory ? "Not a directory" : "Is a directory"), 0);
                return false;
            }
            f.delete();
            return true;
        } catch (IOException | SecurityException e) {
            ctx.warning(fn + "(" + path + "): " + (directory ? "Directory not empty" : "Permission denied"), 0);
            return false;
        }
    }

    /** scandir sorts by bytes (strcmp), not by UTF-16 code units. */
    private static int compareBytes(String x, String y) {
        return x.compareTo(y);
    }
}
