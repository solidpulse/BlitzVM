package dev.phpruntime.builtins;

import com.oracle.truffle.api.TruffleFile;
import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.HostFiles;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpResource;
import dev.phpruntime.runtime.PhpStrings;

import java.io.File;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resources and streams: fopen and friends, directory handles, stat, glob and stream contexts.
 * Local files go through TruffleFile. Network streams are not supported yet.
 */
final class StreamFunctions {

    static final long GLOB_ERR = 0x0004;
    static final long GLOB_MARK = 0x0008;
    static final long GLOB_NOCHECK = 0x0010;
    static final long GLOB_NOSORT = 0x0020;
    static final long GLOB_BRACE = 0x0080;
    static final long GLOB_NOESCAPE = 0x1000;
    static final long GLOB_ONLYDIR = 1L << 30;

    private StreamFunctions() {
    }

    // ------------------------------------------------------------------ resource ids

    /**
     * An id for a new stream. The first stream of a request also allocates PHP's default stream
     * context, which takes an id of its own, so ids match php-cli's.
     */
    static long streamId(PhpContext ctx) {
        ContextState st = ContextState.of(ctx);
        if (!st.defaultStreamContext) {
            st.defaultStreamContext = true;
            st.nextResourceId++;
        }
        return st.nextResourceId++;
    }

    /** Functions such as file_get_contents open a stream internally, which uses up an id. */
    static void consumeStreamId(PhpContext ctx) {
        streamId(ctx);
    }

    private static PhpResource streamResource(PhpContext ctx, PhpStream s) {
        return new PhpResource(streamId(ctx), "stream", s);
    }

    static void register() {
        open();
        readWrite();
        directories();
        files();
        contexts();
        selection();
    }

    private static void selection() {
        Builtins.defRef("stream_select", 4, 5, 0b111, (ctx, a) -> {
            PhpArray read = a[0] instanceof PhpArray p ? p.forWrite() : null;
            PhpArray write = a[1] instanceof PhpArray p ? p.forWrite() : null;
            PhpArray except = a[2] instanceof PhpArray p ? p.forWrite() : null;
            a[0] = read;
            a[1] = write;
            a[2] = except;
            PhpArray[] arrays = {read, write, except};
            int remaining = 0;
            for (PhpArray arr : arrays) {
                if (arr == null) {
                    continue;
                }
                for (java.util.Map.Entry<Object, Object> e : arr.entries()) {
                    PhpStream s = stream(e.getValue(), "stream_select");
                    if (!selectable(s)) {
                        ctx.warning("stream_select(): Cannot represent a stream of type " + s.streamType()
                                + " as a select()able descriptor", 0);
                        arr.remove(e.getKey());
                    }
                }
                remaining += arr.size();
            }
            if (remaining == 0) {
                throw new PhpError(PhpError.VALUE_ERROR, "No stream arrays were passed", null);
            }
            // Local files are always ready; the arrays stay unchanged, as php leaves them.
            return (long) remaining;
        });
        Builtins.def("stream_socket_enable_crypto", 2, 3, (ctx, a) -> {
            boolean enable = PhpOps.toBool(a[1]);
            if (enable && a.length < 3) {
                throw new PhpError(PhpError.VALUE_ERROR,
                        "stream_socket_enable_crypto(): Argument #3 ($crypto_method) must be specified when enabling encryption", null);
            }
            stream(a[0], "stream_socket_enable_crypto");
            // Sockets are a later task; the stream kinds here do not support TLS.
            ctx.warning("stream_socket_enable_crypto(): This stream does not support SSL/crypto", 0);
            return !enable;
        });
    }

    /** Whether stream_select can wait on this kind of stream: local files and stdio only. */
    private static boolean selectable(PhpStream s) {
        return "plainfile".equals(s.wrapperType());
    }

    // ------------------------------------------------------------------ fopen

    private static void open() {
        Builtins.def("fopen", 2, 4, (ctx, a) -> {
            String path = PhpOps.toStr(a[0]);
            String mode = PhpOps.toStr(a[1]);
            PhpStream s = openStream(ctx, path, mode, "fopen");
            return s == null ? false : streamResource(ctx, s);
        });
        Builtins.def("tmpfile", 0, 0, (ctx, a) -> {
            try {
                TruffleFile dir = ctx.env().getPublicTruffleFile(EnvFunctions.tempDir());
                TruffleFile f = ctx.env().createTempFile(dir, "php", null);
                SeekableByteChannel ch = f.newByteChannel(Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE,
                        StandardOpenOption.DELETE_ON_CLOSE));
                ContextState st = ContextState.of(ctx);
                return new PhpResource(st.nextResourceId++, "stream", new PhpStream.FileStream(ch, "r+b", PhpStrings.fromJava(f.getPath())));
            } catch (IOException | SecurityException e) {
                return false;
            }
        });
        Builtins.def("fclose", 1, 1, (ctx, a) -> {
            PhpResource r = resource(a[0], "fclose");
            PhpStream s = stream(r, "fclose");
            try {
                s.close();
            } catch (IOException ignored) {
                // closing is best effort, as in PHP
            }
            r.close();
            return true;
        });
        Builtins.def("is_resource", 1, 1, (ctx, a) -> a[0] instanceof PhpResource r && !r.isClosed());
        Builtins.def("get_resource_type", 1, 1, (ctx, a) -> resource(a[0], "get_resource_type").type());
        Builtins.def("get_resource_id", 1, 1, (ctx, a) -> resource(a[0], "get_resource_id").id());
    }

    /** Opens path with an fopen mode; warns and returns null on failure, as fopen does. */
    static PhpStream openStream(PhpContext ctx, String path, String mode, String fn) {
        String lower = PhpStrings.lower(path);
        if (lower.startsWith("php://")) {
            String what = lower.substring(6);
            return switch (what.contains("/") ? what.substring(0, what.indexOf('/')) : what) {
                case "memory" -> new PhpStream.MemoryStream("w+b", "php://memory", "MEMORY", null);
                case "temp" -> new PhpStream.MemoryStream("w+b", "php://temp", "TEMP", null);
                case "input" -> new PhpStream.MemoryStream("rb", "php://input", "Input",
                        PhpStrings.bytes(ctx.request().body));
                case "output" -> new PhpStream.OutputStreamStream(ctx, null, "wb", "php://output");
                case "stdout" -> new PhpStream.OutputStreamStream(ctx, ctx.env().out(), mode, "php://stdout");
                case "stderr" -> new PhpStream.OutputStreamStream(ctx, ctx.env().err(), mode, "php://stderr");
                default -> {
                    ctx.warning(fn + "(): Invalid php:// URL specified", 0);
                    yield null;
                }
            };
        }
        if (lower.startsWith("file://")) {
            path = path.substring(7);
        } else if (hasScheme(lower)) {
            // TODO: http(s), ftp and other wrappers (network I/O must go through scheduler.blocking).
            ctx.warning(fn + "(): Unable to find the wrapper \"" + path.substring(0, path.indexOf(':'))
                    + "\" - did you forget to enable it when you configured PHP?", 0);
            return null;
        }
        char m = mode.isEmpty() ? '?' : mode.charAt(0);
        boolean plus = mode.indexOf('+') >= 0;
        Set<OpenOption> opts = new HashSet<>();
        switch (m) {
            case 'r' -> opts.add(StandardOpenOption.READ);
            case 'w' -> {
                opts.add(StandardOpenOption.CREATE);
                opts.add(StandardOpenOption.TRUNCATE_EXISTING);
            }
            case 'a', 'c' -> opts.add(StandardOpenOption.CREATE);
            case 'x' -> opts.add(StandardOpenOption.CREATE_NEW);
            default -> throw new PhpError("ValueError", fn + "(): Argument #2 ($mode) must be a valid mode", null);
        }
        if (m != 'r' || plus) {
            opts.add(StandardOpenOption.WRITE);
        }
        if (m == 'r' || plus) {
            opts.add(StandardOpenOption.READ);
        }
        String javaPath = PhpStrings.toJava(path);
        try {
            SeekableByteChannel ch;
            File host = HostFiles.file(ctx, javaPath);
            if (host != null) {
                try {
                    // One open(2); the directory check only runs when the open already failed,
                    // which is where php's "Is a directory" comes from.
                    ch = Files.newByteChannel(host.toPath(), opts);
                } catch (IOException e) {
                    if (host.isDirectory()) {
                        ctx.warning(fn + "(" + path + "): Failed to open stream: Is a directory", 0);
                        return null;
                    }
                    throw e;
                }
            } else {
                TruffleFile f = ctx.env().getPublicTruffleFile(javaPath);
                if (isDir(ctx, path) && m != 'r') {
                    ctx.warning(fn + "(" + path + "): Failed to open stream: Is a directory", 0);
                    return null;
                }
                ch = f.newByteChannel(opts);
            }
            return new PhpStream.FileStream(ch, mode, path);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            ctx.warning(fn + "(" + path + "): Failed to open stream: File exists", 0);
        } catch (java.nio.file.AccessDeniedException e) {
            ctx.warning(fn + "(" + path + "): Failed to open stream: Permission denied", 0);
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            ctx.warning(fn + "(" + path + "): Failed to open stream: No such file or directory", 0);
        }
        return null;
    }

    /** Whether a lowercased path starts with a wrapper scheme (letters, digits, +.- then ://). */
    private static boolean hasScheme(String lower) {
        int i = 0;
        if (lower.isEmpty() || lower.charAt(0) < 'a' || lower.charAt(0) > 'z') {
            return false;
        }
        for (i = 1; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == ':') {
                return i >= 2 && i + 2 < lower.length()
                        && lower.charAt(i + 1) == '/' && lower.charAt(i + 2) == '/';
            }
            boolean schemeChar = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '.' || c == '-';
            if (!schemeChar) {
                return false;
            }
        }
        return false;
    }

    static PhpResource resource(Object v, String fn) {
        if (v instanceof PhpResource r) {
            return r;
        }
        throw new PhpError(PhpError.TYPE_ERROR, fn + "(): Argument #1 ($stream) must be of type resource, "
                + PhpOps.typeName(v) + " given", null);
    }

    static PhpStream stream(PhpResource r, String fn) {
        if (r.handle() instanceof PhpStream s) {
            return s;
        }
        throw new PhpError(PhpError.TYPE_ERROR, fn + "(): supplied resource is not a valid stream resource", null);
    }

    static PhpStream stream(Object v, String fn) {
        return stream(resource(v, fn), fn);
    }

    // ------------------------------------------------------------------ reading and writing

    private static void readWrite() {
        Builtins.def("fread", 2, 2, (ctx, a) -> {
            PhpStream s = stream(a[0], "fread");
            long n = PhpOps.toLong(a[1]);
            if (n <= 0) {
                throw new PhpError("ValueError", "fread(): Argument #2 ($length) must be greater than 0", null);
            }
            if (!s.canRead) {
                return false;
            }
            try {
                return PhpStrings.of(s.readBytes((int) Math.min(n, Integer.MAX_VALUE - 8)));
            } catch (IOException e) {
                return false;
            }
        });
        Builtins.def("fgets", 1, 2, (ctx, a) -> {
            PhpStream s = stream(a[0], "fgets");
            int max = a.length > 1 && a[1] != PhpNull.INSTANCE ? (int) PhpOps.toLong(a[1]) - 1 : -1;
            if (!s.canRead) {
                return false;
            }
            try {
                byte[] line = s.readLine(max);
                return line.length == 0 ? false : PhpStrings.of(line);
            } catch (IOException e) {
                return false;
            }
        });
        Builtins.def("fgetc", 1, 1, (ctx, a) -> {
            PhpStream s = stream(a[0], "fgetc");
            if (!s.canRead) {
                return false;
            }
            try {
                byte[] b = s.readBytes(1);
                return b.length == 0 ? false : PhpStrings.of(b);
            } catch (IOException e) {
                return false;
            }
        });
        Builtins.def("fwrite", 2, 3, StreamFunctions::fwrite);
        Builtins.def("fputs", 2, 3, StreamFunctions::fwrite);
        Builtins.def("feof", 1, 1, (ctx, a) -> stream(a[0], "feof").eof);
        Builtins.def("ftell", 1, 1, (ctx, a) -> stream(a[0], "ftell").tell());
        Builtins.def("fseek", 2, 3, (ctx, a) -> {
            PhpStream s = stream(a[0], "fseek");
            long offset = PhpOps.toLong(a[1]);
            long whence = a.length > 2 ? PhpOps.toLong(a[2]) : 0;
            try {
                long target = switch ((int) whence) {
                    case 1 -> s.tell() + offset;
                    case 2 -> s.size() + offset;
                    default -> offset;
                };
                if (!s.seekable() || !s.seek(target)) {
                    return -1L;
                }
                s.eof = false;
                return 0L;
            } catch (IOException e) {
                return -1L;
            }
        });
        Builtins.def("rewind", 1, 1, (ctx, a) -> {
            PhpStream s = stream(a[0], "rewind");
            try {
                if (!s.seekable() || !s.seek(0)) {
                    return false;
                }
            } catch (IOException e) {
                return false;
            }
            s.eof = false;
            return true;
        });
        Builtins.def("fflush", 1, 1, (ctx, a) -> {
            try {
                stream(a[0], "fflush").flush();
                return true;
            } catch (IOException e) {
                return false;
            }
        });
        Builtins.def("ftruncate", 2, 2, (ctx, a) -> {
            PhpStream s = stream(a[0], "ftruncate");
            long size = PhpOps.toLong(a[1]);
            if (size < 0) {
                throw new PhpError("ValueError", "ftruncate(): Argument #2 ($size) must be greater than or equal to 0", null);
            }
            if (!s.canWrite) {
                return false;
            }
            try {
                s.truncate(size);
                return true;
            } catch (IOException e) {
                return false;
            }
        });
        Builtins.def("flock", 2, 3, (ctx, a) -> {
            stream(a[0], "flock");
            return true;
        });
        Builtins.def("fstat", 1, 1, (ctx, a) -> {
            PhpStream s = stream(a[0], "fstat");
            if (s instanceof PhpStream.FileStream) {
                Object st = stat(ctx, s.uri, false, null);
                if (st instanceof PhpArray arr) {
                    try {
                        // Unflushed writes are already in the channel, so its size is current.
                        long size = s.size();
                        arr.put(7L, size);
                        arr.put("size", size);
                    } catch (IOException ignored) {
                        // keep the size stat() saw
                    }
                }
                return st;
            }
            try {
                return statArray(0, 0, s instanceof PhpStream.MemoryStream ? 0100666 : 010666, 1, 0, 0, 0,
                        s.size(), 0, 0, 0, -1, -1);
            } catch (IOException e) {
                return false;
            }
        });
        Builtins.def("stream_get_contents", 1, 3, (ctx, a) -> {
            PhpStream s = stream(a[0], "stream_get_contents");
            long max = a.length > 1 && a[1] != PhpNull.INSTANCE ? PhpOps.toLong(a[1]) : -1;
            long offset = a.length > 2 ? PhpOps.toLong(a[2]) : -1;
            try {
                if (offset >= 0 && !s.seek(offset)) {
                    return false;
                }
                byte[] data = max < 0 ? s.readAll() : s.readBytes((int) max);
                return PhpStrings.of(data);
            } catch (IOException e) {
                return false;
            }
        });
        Builtins.def("fpassthru", 1, 1, (ctx, a) -> {
            PhpStream s = stream(a[0], "fpassthru");
            try {
                byte[] data = s.readAll();
                ctx.echo(PhpStrings.of(data));
                return (long) data.length;
            } catch (IOException e) {
                return false;
            }
        });
        Builtins.def("stream_get_meta_data", 1, 1, (ctx, a) -> {
            PhpStream s = stream(a[0], "stream_get_meta_data");
            PhpArray m = new PhpArray();
            m.put("timed_out", false);
            m.put("blocked", true);
            m.put("eof", s.eof);
            m.put("wrapper_type", s.wrapperType());
            m.put("stream_type", s.streamType());
            m.put("mode", s.mode);
            m.put("unread_bytes", 0L);
            m.put("seekable", s.seekable());
            m.put("uri", s.uri);
            return m;
        });
        // Timeouts and blocking modes only mean something for sockets, which are not supported yet.
        Builtins.def("stream_set_timeout", 2, 3, (ctx, a) -> {
            stream(a[0], "stream_set_timeout");
            return false;
        });
        Builtins.def("stream_set_blocking", 2, 2, (ctx, a) -> {
            stream(a[0], "stream_set_blocking");
            return true;
        });
        Builtins.def("stream_set_write_buffer", 2, 2, (ctx, a) -> 0L);
        Builtins.def("stream_is_local", 1, 1, (ctx, a) -> {
            if (a[0] instanceof PhpResource) {
                return true;
            }
            String p = PhpStrings.lower(PhpOps.toStr(a[0]));
            return !p.matches("^[a-z][a-z0-9+.-]+://.*") || p.startsWith("file://") || p.startsWith("php://");
        });
        // TODO: stream_socket_client and fsockopen (network streams, through scheduler.blocking).
    }

    private static Object fwrite(PhpContext ctx, Object[] a) {
        PhpStream s = stream(a[0], "fwrite");
        byte[] data = PhpStrings.bytes(PhpOps.toStr(a[1]));
        if (a.length > 2 && a[2] != PhpNull.INSTANCE) {
            long len = PhpOps.toLong(a[2]);
            if (len < data.length) {
                data = java.util.Arrays.copyOf(data, (int) Math.max(0, len));
            }
        }
        if (!s.canWrite) {
            return false;
        }
        try {
            s.write(data);
            return (long) data.length;
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ directories

    private static void directories() {
        Builtins.def("opendir", 1, 2, (ctx, a) -> {
            String path = PhpOps.toStr(a[0]);
            PhpResource r = openDir(ctx, path, "opendir");
            if (r != null) {
                ContextState.of(ctx).lastDir = r;
            }
            return r == null ? false : r;
        });
        Builtins.def("readdir", 0, 1, (ctx, a) -> {
            PhpStream.DirHandle d = dir(ctx, a, "readdir");
            return d.index < d.names.size() ? d.names.get(d.index++) : false;
        });
        Builtins.def("rewinddir", 0, 1, (ctx, a) -> {
            dir(ctx, a, "rewinddir").index = 0;
            return PhpNull.INSTANCE;
        });
        Builtins.def("closedir", 0, 1, (ctx, a) -> {
            PhpResource r = a.length > 0 && a[0] != PhpNull.INSTANCE ? resource(a[0], "closedir") : ContextState.of(ctx).lastDir;
            dir(ctx, a, "closedir");
            r.close();
            return PhpNull.INSTANCE;
        });
    }

    /** Opens a directory as php's opendir resource; null after a warning on failure. */
    static PhpResource openDir(PhpContext ctx, String path, String fn) {
        String javaPath = PhpStrings.toJava(path);
        try {
            List<String> names = new ArrayList<>();
            names.add(".");
            names.add("..");
            File host = HostFiles.file(ctx, javaPath);
            if (host != null) {
                String[] entries = host.list();
                if (entries == null) {
                    throw new IOException();
                }
                for (String name : entries) {
                    names.add(PhpStrings.fromJava(name));
                }
            } else {
                TruffleFile dir = ctx.env().getPublicTruffleFile(javaPath);
                if (!isDir(ctx, path)) {
                    throw new IOException();
                }
                for (TruffleFile child : dir.list()) {
                    names.add(PhpStrings.fromJava(child.getName()));
                }
            }
            return new PhpResource(streamId(ctx), "stream", new PhpStream.DirHandle(names));
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            ctx.warning(fn + "(" + path + "): Failed to open directory: No such file or directory", 0);
            return null;
        }
    }

    private static PhpStream.DirHandle dir(PhpContext ctx, Object[] a, String fn) {
        PhpResource r = a.length > 0 && a[0] != PhpNull.INSTANCE ? resource(a[0], fn) : ContextState.of(ctx).lastDir;
        if (r == null) {
            throw new PhpError(PhpError.TYPE_ERROR, fn + "(): No resource supplied", null);
        }
        if (r.handle() instanceof PhpStream.DirHandle d) {
            return d;
        }
        throw new PhpError(PhpError.TYPE_ERROR, fn + "(): supplied resource is not a valid Directory resource", null);
    }

    // ------------------------------------------------------------------ whole files and metadata

    private static void files() {
        Builtins.def("file", 1, 3, (ctx, a) -> {
            String path = PhpOps.toStr(a[0]);
            long flags = a.length > 1 ? PhpOps.toLong(a[1]) : 0;
            byte[] data = readFile(ctx, path, "file");
            if (data == null) {
                return false;
            }
            String text = PhpStrings.of(data);
            boolean ignoreNl = (flags & 2) != 0;
            boolean skipEmpty = (flags & 4) != 0;
            PhpArray out = new PhpArray();
            int start = 0;
            while (start < text.length()) {
                int nl = text.indexOf('\n', start);
                int end = nl < 0 ? text.length() : nl + 1;
                String line = text.substring(start, end);
                if (ignoreNl && line.endsWith("\n")) {
                    line = line.substring(0, line.length() - 1);
                    if (line.endsWith("\r")) {
                        line = line.substring(0, line.length() - 1);
                    }
                }
                if (!(skipEmpty && ignoreNl && line.isEmpty())) {
                    out.append(line);
                }
                start = end;
            }
            return out;
        });
        Builtins.def("readfile", 1, 3, (ctx, a) -> {
            byte[] data = readFile(ctx, PhpOps.toStr(a[0]), "readfile");
            if (data == null) {
                return false;
            }
            if (data == READ_DIRECTORY) {
                return -1L;
            }
            ctx.echo(PhpStrings.of(data));
            return (long) data.length;
        });
        Builtins.def("md5_file", 1, 2, (ctx, a) -> hashFile(ctx, a, "md5", "md5_file"));
        Builtins.def("sha1_file", 1, 2, (ctx, a) -> hashFile(ctx, a, "sha1", "sha1_file"));
        Builtins.def("stat", 1, 1, (ctx, a) -> stat(ctx, PhpOps.toStr(a[0]), false, "stat"));
        Builtins.def("lstat", 1, 1, (ctx, a) -> stat(ctx, PhpOps.toStr(a[0]), true, "lstat"));
        Builtins.def("fileperms", 1, 1, (ctx, a) -> statField(ctx, PhpOps.toStr(a[0]), "fileperms", 2));
        Builtins.def("fileinode", 1, 1, (ctx, a) -> statField(ctx, PhpOps.toStr(a[0]), "fileinode", 1));
        Builtins.def("fileowner", 1, 1, (ctx, a) -> statField(ctx, PhpOps.toStr(a[0]), "fileowner", 4));
        Builtins.def("filegroup", 1, 1, (ctx, a) -> statField(ctx, PhpOps.toStr(a[0]), "filegroup", 5));
        Builtins.def("fileatime", 1, 1, (ctx, a) -> statField(ctx, PhpOps.toStr(a[0]), "fileatime", 8));
        Builtins.def("filectime", 1, 1, (ctx, a) -> statField(ctx, PhpOps.toStr(a[0]), "filectime", 10));
        Builtins.def("is_link", 1, 1, (ctx, a) -> {
            String path = PhpOps.toStr(a[0]);
            String javaPath = PhpStrings.toJava(path);
            try {
                File host = HostFiles.file(ctx, javaPath);
                if (host != null) {
                    // A trailing slash follows the link, and is_link() is false for its target.
                    return !HostFiles.directoryOnly(javaPath) && Files.isSymbolicLink(host.toPath());
                }
                return ctx.env().getPublicTruffleFile(javaPath).isSymbolicLink();
            } catch (SecurityException | IllegalArgumentException e) {
                return false;
            }
        });
        Builtins.def("chmod", 2, 2, (ctx, a) -> {
            String path = PhpOps.toStr(a[0]);
            long mode = PhpOps.toLong(a[1]);
            try {
                ctx.env().getPublicTruffleFile(PhpStrings.toJava(path)).setPosixPermissions(permissions(mode));
                return true;
            } catch (IOException | SecurityException | IllegalArgumentException | UnsupportedOperationException e) {
                ctx.warning("chmod(): No such file or directory", 0);
                return false;
            }
        });
        Builtins.def("umask", 0, 1, (ctx, a) -> {
            ContextState st = ContextState.of(ctx);
            long old = st.umask;
            if (a.length > 0 && a[0] != PhpNull.INSTANCE) {
                st.umask = PhpOps.toLong(a[0]) & 0777;
            }
            return old;
        });
        Builtins.def("touch", 1, 3, (ctx, a) -> {
            String path = PhpOps.toStr(a[0]);
            try {
                TruffleFile f = ctx.env().getPublicTruffleFile(PhpStrings.toJava(path));
                if (!exists(ctx, path)) {
                    f.newByteChannel(Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE)).close();
                }
                long now = System.currentTimeMillis() / 1000;
                long mtime = a.length > 1 && a[1] != PhpNull.INSTANCE ? PhpOps.toLong(a[1]) : now;
                long atime = a.length > 2 && a[2] != PhpNull.INSTANCE ? PhpOps.toLong(a[2]) : mtime;
                f.setLastModifiedTime(FileTime.fromMillis(mtime * 1000));
                f.setLastAccessTime(FileTime.fromMillis(atime * 1000));
                return true;
            } catch (IOException | SecurityException | IllegalArgumentException e) {
                ctx.warning("touch(): Unable to create file " + path + " because No such file or directory", 0);
                return false;
            }
        });
        Builtins.def("copy", 2, 3, (ctx, a) -> {
            String from = PhpOps.toStr(a[0]);
            String to = PhpOps.toStr(a[1]);
            try {
                TruffleFile src = ctx.env().getPublicTruffleFile(PhpStrings.toJava(from));
                if (!isFile(ctx, from)) {
                    throw new IOException();
                }
                consumeStreamId(ctx);
                consumeStreamId(ctx);
                src.copy(ctx.env().getPublicTruffleFile(PhpStrings.toJava(to)), StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (IOException | SecurityException | IllegalArgumentException e) {
                ctx.warning("copy(" + from + "): Failed to open stream: No such file or directory", 0);
                return false;
            }
        });
        Builtins.def("rename", 2, 3, (ctx, a) -> {
            FileFunctions.clearRealpathCache();
            String from = PhpOps.toStr(a[0]);
            String to = PhpOps.toStr(a[1]);
            try {
                TruffleFile src = ctx.env().getPublicTruffleFile(PhpStrings.toJava(from));
                if (!src.exists(LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException();
                }
                src.move(ctx.env().getPublicTruffleFile(PhpStrings.toJava(to)), StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (IOException | SecurityException | IllegalArgumentException e) {
                ctx.warning("rename(" + from + "," + to + "): No such file or directory", 0);
                return false;
            }
        });
        Builtins.def("tempnam", 2, 2, (ctx, a) -> {
            String dirName = PhpOps.toStr(a[0]);
            String prefix = FileFunctions.basename(PhpOps.toStr(a[1]), "");
            if (prefix.length() > 63) {
                prefix = prefix.substring(0, 63);
            }
            try {
                TruffleFile dir = dirName.isEmpty() ? null : ctx.env().getPublicTruffleFile(PhpStrings.toJava(dirName));
                if (dir == null || !isDir(ctx, dirName) || !isWritable(ctx, dirName)) {
                    ctx.warning("tempnam(): file created in the system's temporary directory", 0);
                    dir = ctx.env().getPublicTruffleFile(EnvFunctions.tempDir());
                }
                dir = dir.getCanonicalFile();
                java.util.Random rnd = java.util.concurrent.ThreadLocalRandom.current();
                String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
                for (int attempt = 0; attempt < 100; attempt++) {
                    // PHP 8.5 names are the prefix, 13 random [a-z0-9], then mkstemp's 6 characters.
                    StringBuilder name = new StringBuilder(prefix);
                    for (int i = 0; i < 13; i++) {
                        name.append(alphabet.charAt(26 + rnd.nextInt(36)));
                    }
                    for (int i = 0; i < 6; i++) {
                        name.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
                    }
                    TruffleFile f = dir.resolve(PhpStrings.toJava(name.toString()));
                    try {
                        f.newByteChannel(Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)).close();
                        return PhpStrings.fromJava(f.getPath());
                    } catch (java.nio.file.FileAlreadyExistsException retry) {
                        // try another name
                    }
                }
                return false;
            } catch (IOException | SecurityException | IllegalArgumentException e) {
                return false;
            }
        });
        Builtins.def("clearstatcache", 0, 2, (ctx, a) -> {
            if (a.length > 0 && PhpOps.toBool(a[0])) {
                FileFunctions.clearRealpathCache();
            }
            return PhpNull.INSTANCE;
        });
        Builtins.def("disk_free_space", 1, 1, (ctx, a) -> space(PhpOps.toStr(a[0]), true));
        Builtins.def("diskfreespace", 1, 1, (ctx, a) -> space(PhpOps.toStr(a[0]), true));
        Builtins.def("disk_total_space", 1, 1, (ctx, a) -> space(PhpOps.toStr(a[0]), false));
        Builtins.def("glob", 1, 2, (ctx, a) -> glob(ctx, PhpOps.toStr(a[0]), a.length > 1 ? PhpOps.toLong(a[1]) : 0));
    }

    /** Reads a whole local file for file(), readfile() and friends; warns and returns null if missing. */
    static byte[] readFile(PhpContext ctx, String path, String fn) {
        String javaPath = PhpStrings.toJava(path);
        try {
            byte[] data;
            File host = HostFiles.file(ctx, javaPath);
            if (host != null) {
                try {
                    // One open/read/close; php does not stat first either.
                    data = Files.readAllBytes(host.toPath());
                } catch (IOException e) {
                    if (host.isDirectory()) {
                        consumeStreamId(ctx);
                        return READ_DIRECTORY;
                    }
                    throw e;
                }
            } else {
                TruffleFile f = ctx.env().getPublicTruffleFile(javaPath);
                try {
                    data = f.readAllBytes();
                } catch (IOException e) {
                    if (f.isDirectory()) {
                        consumeStreamId(ctx);
                        return READ_DIRECTORY;
                    }
                    throw e;
                }
            }
            consumeStreamId(ctx);
            return data;
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            ctx.warning(fn + "(" + path + "): Failed to open stream: No such file or directory", 0);
            return null;
        }
    }

    /**
     * php opens a directory as a stream and reads no bytes from it: file(), md5_file() and
     * sha1_file() behave as for an empty file, while readfile() returns -1.
     */
    static final byte[] READ_DIRECTORY = new byte[0];

    private static Object hashFile(PhpContext ctx, Object[] a, String algo, String fn) {
        byte[] data = readFile(ctx, PhpOps.toStr(a[0]), fn);
        if (data == null) {
            return false;
        }
        byte[] digest = HashFunctions.digest(algo, data, fn);
        boolean binary = a.length > 1 && PhpOps.toBool(a[1]);
        return binary ? PhpStrings.of(digest) : java.util.HexFormat.of().formatHex(digest);
    }

    private static final List<TruffleFile.AttributeDescriptor<?>> STAT_ATTRS = List.of(
            TruffleFile.UNIX_DEV, TruffleFile.UNIX_INODE, TruffleFile.UNIX_MODE, TruffleFile.UNIX_NLINK,
            TruffleFile.UNIX_UID, TruffleFile.UNIX_GID, TruffleFile.UNIX_RDEV, TruffleFile.SIZE,
            TruffleFile.LAST_ACCESS_TIME, TruffleFile.LAST_MODIFIED_TIME, TruffleFile.UNIX_CTIME);

    /** stat()/lstat(): PHP's 26-entry array, or false with a warning when fn is not null. */
    static Object stat(PhpContext ctx, String path, boolean noFollow, String fn) {
        String javaPath = PhpStrings.toJava(path);
        File host = HostFiles.file(ctx, javaPath);
        if (host != null) {
            try {
                // php's stat(2) with a trailing slash requires a directory and then follows the
                // link even for lstat; File drops the slash, so ask the kernel with one stat.
                boolean directoryOnly = HostFiles.directoryOnly(javaPath);
                if (directoryOnly && !host.isDirectory()) {
                    throw new IOException();
                }
                LinkOption[] opts = noFollow && !directoryOnly
                        ? new LinkOption[]{LinkOption.NOFOLLOW_LINKS} : new LinkOption[0];
                // An explicit list, not "unix:*": the star also resolves owner and group names
                // through getpwuid/getgrgid, which php's stat(2) does not.
                java.util.Map<String, Object> at = Files.readAttributes(host.toPath(),
                        "unix:dev,ino,mode,nlink,uid,gid,rdev,size,lastAccessTime,lastModifiedTime,ctime", opts);
                long size = num((Number) at.get("size"));
                return statArray(num((Number) at.get("dev")), num((Number) at.get("ino")),
                        num((Number) at.get("mode")), num((Number) at.get("nlink")),
                        num((Number) at.get("uid")), num((Number) at.get("gid")),
                        num((Number) at.get("rdev")), size,
                        secs((FileTime) at.get("lastAccessTime")),
                        secs((FileTime) at.get("lastModifiedTime")),
                        secs((FileTime) at.get("ctime")),
                        4096, (size + 511) / 512);
            } catch (IOException | SecurityException | IllegalArgumentException | UnsupportedOperationException e) {
                if (fn != null) {
                    ctx.warning(fn + "(): " + (fn.equals("lstat") ? "Lstat" : "stat") + " failed for " + path, 0);
                }
                return false;
            }
        }
        try {
            TruffleFile f = ctx.env().getPublicTruffleFile(javaPath);
            Boolean hostExists = noFollow ? null : HostFiles.exists(ctx, javaPath);
            if (hostExists != null && !hostExists) {
                if (fn != null) {
                    ctx.warning(fn + "(): " + (fn.equals("lstat") ? "Lstat" : "stat") + " failed for " + path, 0);
                }
                return false;
            }
            LinkOption[] opts = noFollow ? new LinkOption[]{LinkOption.NOFOLLOW_LINKS} : new LinkOption[0];
            TruffleFile.Attributes at = f.getAttributes(STAT_ATTRS, opts);
            long size = num(at.get(TruffleFile.SIZE));
            return statArray(num(at.get(TruffleFile.UNIX_DEV)), num(at.get(TruffleFile.UNIX_INODE)),
                    num(at.get(TruffleFile.UNIX_MODE)), num(at.get(TruffleFile.UNIX_NLINK)),
                    num(at.get(TruffleFile.UNIX_UID)), num(at.get(TruffleFile.UNIX_GID)),
                    num(at.get(TruffleFile.UNIX_RDEV)), size, secs(at.get(TruffleFile.LAST_ACCESS_TIME)),
                    secs(at.get(TruffleFile.LAST_MODIFIED_TIME)), secs(at.get(TruffleFile.UNIX_CTIME)),
                    4096, (size + 511) / 512);
        } catch (IOException | SecurityException | IllegalArgumentException | UnsupportedOperationException e) {
            if (fn != null) {
                ctx.warning(fn + "(): " + (fn.equals("lstat") ? "Lstat" : "stat") + " failed for " + path, 0);
            }
            return false;
        }
    }

    private static long num(Number n) {
        return n == null ? 0 : n.longValue();
    }

    private static long secs(FileTime t) {
        return t == null ? 0 : t.toMillis() / 1000;
    }

    private static final String[] STAT_NAMES = {"dev", "ino", "mode", "nlink", "uid", "gid", "rdev", "size",
        "atime", "mtime", "ctime", "blksize", "blocks"};

    private static PhpArray statArray(long... v) {
        PhpArray out = new PhpArray();
        for (int i = 0; i < v.length; i++) {
            out.put((long) i, v[i]);
        }
        for (int i = 0; i < v.length; i++) {
            out.put(STAT_NAMES[i], v[i]);
        }
        return out;
    }

    private static Object statField(PhpContext ctx, String path, String fn, int index) {
        Object st = stat(ctx, path, false, null);
        if (st instanceof PhpArray arr) {
            return arr.get((long) index);
        }
        ctx.warning(fn + "(): stat failed for " + path, 0);
        return false;
    }

    private static Set<PosixFilePermission> permissions(long mode) {
        Set<PosixFilePermission> p = EnumSet.noneOf(PosixFilePermission.class);
        PosixFilePermission[] order = {
            PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_READ,
            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_READ,
            PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ};
        for (int i = 0; i < order.length; i++) {
            if ((mode & (1L << i)) != 0) {
                p.add(order[i]);
            }
        }
        return p;
    }

    private static Object space(String path, boolean free) {
        java.io.File f = new java.io.File(PhpStrings.toJava(path));
        if (!f.exists()) {
            return false;
        }
        return (double) (free ? f.getUsableSpace() : f.getTotalSpace());
    }

    // ------------------------------------------------------------------ glob

    static Object glob(PhpContext ctx, String pattern, long flags) {
        List<String> alternatives = (flags & GLOB_BRACE) != 0 ? expandBraces(pattern) : List.of(pattern);
        boolean escape = (flags & GLOB_NOESCAPE) == 0;
        // The type is only needed to filter or mark directories; a plain glob never stats a
        // match that a directory listing produced.
        boolean needType = (flags & (GLOB_ONLYDIR | GLOB_MARK)) != 0;
        PhpArray out = new PhpArray();
        for (String alt : alternatives) {
            List<String> matches = new ArrayList<>();
            // -1 keeps the empty segments of a trailing slash, which php echoes back.
            String[] segments = alt.split("/", -1);
            expand(ctx, alt.startsWith("/") ? "/" : "", segments, alt.startsWith("/") ? 1 : 0, escape, matches, false);
            List<String> kept = new ArrayList<>(matches.size());
            for (String m : matches) {
                boolean isDir = needType && (m.endsWith("/") || isDir(ctx, m));
                if ((flags & GLOB_ONLYDIR) != 0 && !isDir) {
                    continue;
                }
                kept.add((flags & GLOB_MARK) != 0 && isDir && !m.endsWith("/") ? m + "/" : m);
            }
            if ((flags & GLOB_NOSORT) == 0) {
                kept.sort(String::compareTo);
            }
            for (String k : kept) {
                out.append(k);
            }
            // php expands braces first and applies GLOB_NOCHECK to each alternative.
            if (kept.isEmpty() && (flags & GLOB_NOCHECK) != 0) {
                out.append(alt);
            }
        }
        return out;
    }

    /**
     * Expands one alternative's segments into matches. A path produced by a directory listing is
     * known to exist, so the leaf needs no stat and a dangling symlink still matches, as php's
     * glob reports readdir entries without following the link. Prefixes keep their separators:
     * a pattern ending in a star adds no trailing slash, one ending in a slash keeps it.
     */
    private static void expand(PhpContext ctx, String prefix, String[] segments, int i, boolean escape,
                               List<String> out, boolean known) {
        if (i == segments.length) {
            if (!prefix.isEmpty() && (known || existsNoFollow(ctx, prefix))) {
                out.add(prefix);
            }
            return;
        }
        String seg = segments[i];
        String sep = i + 1 < segments.length ? "/" : "";
        if (seg.isEmpty()) {
            expand(ctx, prefix + sep, segments, i + 1, escape, out, known);
            return;
        }
        if (!hasMagic(seg, escape)) {
            String literal = escape ? unescape(seg) : seg;
            String next = prefix + literal + sep;
            if (sep.isEmpty()) {
                expand(ctx, next, segments, i + 1, escape, out, false);
            } else if (isDir(ctx, prefix + literal)) {
                expand(ctx, next, segments, i + 1, escape, out, true);
            }
            return;
        }
        String dirPath = prefix.isEmpty() ? "." : prefix;
        String[] names = listNames(ctx, dirPath);
        if (names == null) {
            return;
        }
        boolean dotSegment = seg.charAt(0) == '.'
                || (escape && seg.length() > 1 && seg.charAt(0) == '\\' && seg.charAt(1) == '.');
        if (dotSegment) {
            // readdir reports these two entries and php matches them for a leading-dot pattern.
            expandName(ctx, prefix, ".", seg, sep, segments, i, escape, out);
            expandName(ctx, prefix, "..", seg, sep, segments, i, escape, out);
        }
        for (String name : names) {
            if (name.startsWith(".") && !dotSegment) {
                continue;
            }
            expandName(ctx, prefix, name, seg, sep, segments, i, escape, out);
        }
    }

    private static void expandName(PhpContext ctx, String prefix, String name, String seg, String sep,
                                   String[] segments, int i, boolean escape, List<String> out) {
        if (!matchesSegment(seg, name, escape)) {
            return;
        }
        if (!sep.isEmpty() && !isDir(ctx, prefix + name)) {
            return;
        }
        expand(ctx, prefix + name + sep, segments, i + 1, escape, out, true);
    }

    /** Directory entries for glob()'s next segment, or null when the directory cannot be listed. */
    private static String[] listNames(PhpContext ctx, String dirPath) {
        String javaPath = PhpStrings.toJava(dirPath);
        File host = HostFiles.file(ctx, javaPath);
        if (host != null) {
            String[] entries = host.list();
            if (entries == null) {
                return null;
            }
            for (int i = 0; i < entries.length; i++) {
                entries[i] = PhpStrings.fromJava(entries[i]);
            }
            return entries;
        }
        try {
            TruffleFile dir = ctx.env().getPublicTruffleFile(javaPath);
            if (!isDir(ctx, dirPath)) {
                return null;
            }
            List<String> names = new ArrayList<>();
            for (TruffleFile child : dir.list()) {
                names.add(PhpStrings.fromJava(child.getName()));
            }
            return names.toArray(new String[0]);
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            // unreadable directories contribute nothing, as with GLOB_ERR unset
            return null;
        }
    }

    /** Removes the backslashes from an escaped literal segment. */
    private static String unescape(String seg) {
        int backslash = seg.indexOf('\\');
        if (backslash < 0) {
            return seg;
        }
        StringBuilder out = new StringBuilder(seg.length());
        for (int i = 0; i < seg.length(); i++) {
            char c = seg.charAt(i);
            if (c == '\\' && i + 1 < seg.length()) {
                c = seg.charAt(++i);
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean hasMagic(String seg, boolean escape) {
        for (int i = 0; i < seg.length(); i++) {
            char c = seg.charAt(i);
            if (c == '\\' && escape) {
                i++;
            } else if (c == '*' || c == '?' || c == '[') {
                return true;
            }
        }
        return false;
    }

    /**
     * glob(3) matching for one path segment, byte by byte, as if with FNM_PATHNAME unset: '*'
     * matches any run of bytes (including none), '?' one byte, '[...]' a class with '!' negation
     * and '-' ranges, and an unterminated '[' is a literal. A backslash escapes unless
     * GLOB_NOESCAPE is set. Written out rather than compiled to a regex: patterns repeat on every
     * front-page request and Pattern.compile was the visible cost in glob.
     */
    static boolean matchesSegment(String pattern, String name, boolean escape) {
        int p = 0;
        int n = 0;
        int star = -1;
        int starAt = 0;
        while (n < name.length()) {
            if (p < pattern.length()) {
                char c = pattern.charAt(p);
                if (c == '*') {
                    star = p++;
                    starAt = n;
                    continue;
                }
                if (c == '?') {
                    p++;
                    n++;
                    continue;
                }
                if (c == '[') {
                    int close = classEnd(pattern, p, escape);
                    if (close < 0) {
                        if (name.charAt(n) == '[') {
                            p++;
                            n++;
                            continue;
                        }
                    } else if (classMatches(pattern, p, close, name.charAt(n), escape)) {
                        p = close + 1;
                        n++;
                        continue;
                    }
                } else if (c == '\\' && escape && p + 1 < pattern.length()) {
                    if (pattern.charAt(p + 1) == name.charAt(n)) {
                        p += 2;
                        n++;
                        continue;
                    }
                } else if (c == name.charAt(n)) {
                    p++;
                    n++;
                    continue;
                }
            }
            if (star < 0) {
                return false;
            }
            p = star + 1;
            n = ++starAt;
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') {
            p++;
        }
        return p == pattern.length();
    }

    /** The index of the ']' that closes the class opened at {@code open}, or -1 if there is none. */
    private static int classEnd(String pattern, int open, boolean escape) {
        int i = open + 1;
        if (i < pattern.length() && pattern.charAt(i) == '!') {
            i++;
        }
        if (i < pattern.length() && pattern.charAt(i) == ']') {
            i++;
        }
        for (; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && escape) {
                i++;
            } else if (c == ']') {
                return i;
            }
        }
        return -1;
    }

    /** Whether a character is a member of the class between {@code open} and {@code close}. */
    private static boolean classMatches(String pattern, int open, int close, char ch, boolean escape) {
        int i = open + 1;
        boolean negate = i < close && pattern.charAt(i) == '!';
        if (negate) {
            i++;
        }
        boolean matched = false;
        while (i < close) {
            char lo = pattern.charAt(i);
            if (lo == '\\' && escape && i + 1 < close) {
                lo = pattern.charAt(++i);
            }
            i++;
            if (i + 1 < close && pattern.charAt(i) == '-') {
                char hi = pattern.charAt(i + 1);
                if (hi == '\\' && escape && i + 2 < close) {
                    hi = pattern.charAt(i + 2);
                    i++;
                }
                if (ch >= lo && ch <= hi) {
                    matched = true;
                }
                i += 2;
            } else if (ch == lo) {
                matched = true;
            }
        }
        return matched != negate;
    }

    /** GLOB_BRACE: "a{b,c}d" becomes ["abd", "acd"], nested braces included, left to right. */
    static List<String> expandBraces(String p) {
        int open = -1;
        int depth = 0;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '{') {
                if (depth++ == 0) {
                    open = i;
                }
            } else if (c == '}' && depth > 0 && --depth == 0) {
                List<String> parts = new ArrayList<>();
                int start = open + 1;
                int d = 0;
                for (int j = open + 1; j < i; j++) {
                    char x = p.charAt(j);
                    if (x == '{') {
                        d++;
                    } else if (x == '}') {
                        d--;
                    } else if (x == ',' && d == 0) {
                        parts.add(p.substring(start, j));
                        start = j + 1;
                    }
                }
                parts.add(p.substring(start, i));
                List<String> out = new ArrayList<>();
                for (String part : parts) {
                    out.addAll(expandBraces(p.substring(0, open) + part + p.substring(i + 1)));
                }
                return out;
            }
        }
        return List.of(p);
    }

    private static boolean exists(PhpContext ctx, String path) {
        String javaPath = PhpStrings.toJava(path);
        Boolean host = HostFiles.exists(ctx, javaPath);
        if (host != null) {
            return host;
        }
        try {
            return ctx.env().getPublicTruffleFile(javaPath).exists();
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * lstat-based existence for glob(): readdir lists a dangling symlink, and php's glob returns
     * it for a literal pattern too. A trailing slash still requires a directory, as the kernel's
     * stat does.
     */
    private static boolean existsNoFollow(PhpContext ctx, String path) {
        String javaPath = PhpStrings.toJava(path);
        File host = HostFiles.file(ctx, javaPath);
        if (host != null) {
            if (HostFiles.directoryOnly(javaPath)) {
                return host.isDirectory();
            }
            return Files.exists(host.toPath(), LinkOption.NOFOLLOW_LINKS);
        }
        try {
            return ctx.env().getPublicTruffleFile(javaPath).exists(LinkOption.NOFOLLOW_LINKS);
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isFile(PhpContext ctx, String path) {
        String javaPath = PhpStrings.toJava(path);
        Boolean host = HostFiles.isFile(ctx, javaPath);
        if (host != null) {
            return host;
        }
        try {
            return ctx.env().getPublicTruffleFile(javaPath).isRegularFile();
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isDir(PhpContext ctx, String path) {
        String javaPath = PhpStrings.toJava(path);
        Boolean host = HostFiles.isDirectory(ctx, javaPath);
        if (host != null) {
            return host;
        }
        try {
            return ctx.env().getPublicTruffleFile(javaPath).isDirectory();
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isWritable(PhpContext ctx, String path) {
        String javaPath = PhpStrings.toJava(path);
        Boolean host = HostFiles.isWritable(ctx, javaPath);
        if (host != null) {
            return host;
        }
        try {
            TruffleFile f = ctx.env().getPublicTruffleFile(javaPath);
            return f.exists() && f.isWritable();
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ stream contexts

    private static void contexts() {
        Builtins.def("stream_context_create", 0, 2, (ctx, a) -> {
            PhpArray options = a.length > 0 && a[0] instanceof PhpArray o ? o.share() : new PhpArray();
            ContextState st = ContextState.of(ctx);
            return new PhpResource(st.nextResourceId++, "stream-context", new PhpArray[]{options});
        });
        Builtins.def("stream_context_get_options", 1, 1, (ctx, a) -> {
            PhpResource r = resource(a[0], "stream_context_get_options");
            if (r.handle() instanceof PhpArray[] holder) {
                return holder[0].share();
            }
            return new PhpArray();
        });
        Builtins.def("stream_context_set_option", 2, 4, (ctx, a) -> {
            PhpResource r = resource(a[0], "stream_context_set_option");
            if (!(r.handle() instanceof PhpArray[] holder)) {
                return false;
            }
            PhpArray opts = holder[0].forWrite();
            if (a[1] instanceof PhpArray all) {
                for (var e : all.entries()) {
                    opts.put(e.getKey(), e.getValue());
                }
            } else if (a.length == 4) {
                Object wrapper = PhpOps.toStr(a[1]);
                PhpArray w = opts.get(wrapper) instanceof PhpArray x ? x.forWrite() : new PhpArray();
                w.put(PhpOps.toStr(a[2]), a[3]);
                opts.put(wrapper, w);
            }
            holder[0] = opts;
            return true;
        });
        Builtins.def("stream_get_wrappers", 0, 0, (ctx, a) -> PhpArray.ofList(List.of(
                "https", "ftps", "compress.zlib", "php", "file", "glob", "data", "http", "ftp", "phar", "zip")));
    }
}
