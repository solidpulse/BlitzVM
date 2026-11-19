package dev.phpruntime.builtins;

import com.oracle.truffle.api.TruffleFile;
import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpResource;
import dev.phpruntime.runtime.PhpStrings;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
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
import java.util.regex.Pattern;

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
        } else if (lower.matches("^[a-z][a-z0-9+.-]+://.*")) {
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
        try {
            TruffleFile f = ctx.env().getPublicTruffleFile(PhpStrings.toJava(path));
            if (f.isDirectory() && m != 'r') {
                ctx.warning(fn + "(" + path + "): Failed to open stream: Is a directory", 0);
                return null;
            }
            SeekableByteChannel ch = f.newByteChannel(opts);
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
        try {
            TruffleFile dir = ctx.env().getPublicTruffleFile(PhpStrings.toJava(path));
            if (!dir.isDirectory()) {
                throw new IOException();
            }
            List<String> names = new ArrayList<>();
            names.add(".");
            names.add("..");
            for (TruffleFile child : dir.list()) {
                names.add(PhpStrings.fromJava(child.getName()));
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
            try {
                return ctx.env().getPublicTruffleFile(PhpStrings.toJava(PhpOps.toStr(a[0]))).isSymbolicLink();
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
                if (!f.exists()) {
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
                if (!src.isRegularFile()) {
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
                if (dir == null || !dir.isDirectory() || !dir.isWritable()) {
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
        try {
            TruffleFile f = ctx.env().getPublicTruffleFile(PhpStrings.toJava(path));
            if (!f.isRegularFile()) {
                throw new IOException();
            }
            byte[] data = f.readAllBytes();
            consumeStreamId(ctx);
            return data;
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            ctx.warning(fn + "(" + path + "): Failed to open stream: No such file or directory", 0);
            return null;
        }
    }

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
        try {
            TruffleFile f = ctx.env().getPublicTruffleFile(PhpStrings.toJava(path));
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
        PhpArray out = new PhpArray();
        for (String alt : alternatives) {
            List<String> matches = new ArrayList<>();
            expand(ctx, "", alt.startsWith("/") ? "/" : "", alt.split("/"), alt.startsWith("/") ? 1 : 0, escape, matches);
            List<String> kept = new ArrayList<>();
            for (String m : matches) {
                boolean isDir = isDir(ctx, m);
                if ((flags & GLOB_ONLYDIR) != 0 && !isDir) {
                    continue;
                }
                kept.add((flags & GLOB_MARK) != 0 && isDir ? m + "/" : m);
            }
            if ((flags & GLOB_NOSORT) == 0) {
                kept.sort(String::compareTo);
            }
            for (String k : kept) {
                out.append(k);
            }
        }
        if (out.size() == 0 && (flags & GLOB_NOCHECK) != 0) {
            out.append(pattern);
        }
        return out;
    }

    private static void expand(PhpContext ctx, String unused, String prefix, String[] segments, int i, boolean escape, List<String> out) {
        if (i == segments.length) {
            if (!prefix.isEmpty() && exists(ctx, prefix)) {
                out.add(prefix.endsWith("/") && prefix.length() > 1 ? prefix.substring(0, prefix.length() - 1) : prefix);
            }
            return;
        }
        String seg = segments[i];
        String sep = i + 1 < segments.length ? "/" : "";
        if (seg.isEmpty()) {
            expand(ctx, unused, prefix, segments, i + 1, escape, out);
            return;
        }
        if (!hasMagic(seg, escape)) {
            String literal = escape ? seg.replaceAll("\\\\(.)", "$1") : seg;
            String next = prefix + literal + sep;
            if (sep.isEmpty() || isDir(ctx, prefix + literal)) {
                expand(ctx, unused, next, segments, i + 1, escape, out);
            }
            return;
        }
        Pattern re = segmentRegex(seg, escape);
        String dirPath = prefix.isEmpty() ? "." : prefix;
        try {
            TruffleFile dir = ctx.env().getPublicTruffleFile(PhpStrings.toJava(dirPath));
            if (!dir.isDirectory()) {
                return;
            }
            List<String> names = new ArrayList<>();
            for (TruffleFile child : dir.list()) {
                names.add(PhpStrings.fromJava(child.getName()));
            }
            names.sort(String::compareTo);
            for (String name : names) {
                if (name.startsWith(".") && !seg.startsWith(".")) {
                    continue;
                }
                if (!re.matcher(name).matches()) {
                    continue;
                }
                if (!sep.isEmpty() && !isDir(ctx, prefix + name)) {
                    continue;
                }
                expand(ctx, unused, prefix + name + sep, segments, i + 1, escape, out);
            }
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            // unreadable directories contribute nothing, as with GLOB_ERR unset
        }
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

    private static Pattern segmentRegex(String seg, boolean escape) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < seg.length(); i++) {
            char c = seg.charAt(i);
            if (c == '\\' && escape && i + 1 < seg.length()) {
                re.append(Pattern.quote(String.valueOf(seg.charAt(++i))));
            } else if (c == '*') {
                re.append("[^/]*");
            } else if (c == '?') {
                re.append("[^/]");
            } else if (c == '[') {
                int close = seg.indexOf(']', i + 2);
                if (close < 0) {
                    re.append("\\[");
                    continue;
                }
                String body = seg.substring(i + 1, close);
                boolean negate = body.startsWith("!") || body.startsWith("^");
                if (negate) {
                    body = body.substring(1);
                }
                re.append('[').append(negate ? "^" : "");
                for (char b : body.toCharArray()) {
                    re.append(b == '-' ? "-" : (Character.isLetterOrDigit(b) ? String.valueOf(b) : "\\" + b));
                }
                re.append(']');
                i = close;
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString());
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
        try {
            return ctx.env().getPublicTruffleFile(PhpStrings.toJava(path)).exists();
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isDir(PhpContext ctx, String path) {
        try {
            return ctx.env().getPublicTruffleFile(PhpStrings.toJava(path)).isDirectory();
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
