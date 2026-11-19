package dev.phpruntime.runtime;

import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.TruffleLanguage;
import dev.phpruntime.PhpContext;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Existence questions for the host's default file system, answered without exceptions.
 *
 * <p>{@link TruffleFile#exists()}, {@link TruffleFile#isDirectory()} and
 * {@link TruffleFile#isRegularFile()} on the default host file system go through NIO, which
 * reports a missing file by constructing and throwing a {@link java.nio.file.NoSuchFileException}.
 * WordPress probes many files that do not exist, and a profile of its front page spent 0.7% of
 * CPU on those stack traces. {@link File} makes the same stat or access call and returns false
 * when it fails instead. A sandboxed, read-only or virtual public file system keeps using
 * TruffleFile, so its behaviour is unchanged.
 *
 * <p>Whether the context uses the host default is decided once, by looking at the file system
 * object behind its public TruffleFiles. That object is package-private in Truffle, so the lookup
 * is reflective; if the internals move, the answer is "not the host default" and everything
 * falls back to TruffleFile.
 */
public final class HostFiles {

    private static final Method SPI_FILE_SYSTEM = method(TruffleFile.class, "getSPIFileSystem");
    private static final Field NIO_IS_DEFAULT =
            field("com.oracle.truffle.polyglot.FileSystems$NIOFileSystem", "isDefault");

    private HostFiles() {
    }

    /** Whether the context's public file system is the host's default file system. */
    public static boolean isDefaultHostFileSystem(TruffleLanguage.Env env) {
        Method spi = SPI_FILE_SYSTEM;
        Field isDefault = NIO_IS_DEFAULT;
        if (spi == null || isDefault == null) {
            return false;
        }
        try {
            Object fileSystem = spi.invoke(env.getPublicTruffleFile("."));
            return fileSystem != null && fileSystem.getClass() == isDefault.getDeclaringClass()
                    && isDefault.getBoolean(fileSystem);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** file_exists() on the host file system, or null when another file system must answer. */
    public static Boolean exists(PhpContext ctx, String path) {
        File file = file(ctx, path);
        if (file == null) {
            return null;
        }
        // Java drops a trailing slash and stats the file itself; php's stat keeps it, so a
        // regular file with a trailing slash does not exist and is not a file.
        return directoryOnly(path) ? file.isDirectory() : file.exists();
    }

    /** is_file() on the host file system, or null when another file system must answer. */
    public static Boolean isFile(PhpContext ctx, String path) {
        File file = file(ctx, path);
        return file == null ? null : !directoryOnly(path) && file.isFile();
    }

    /** is_dir() on the host file system, or null when another file system must answer. */
    public static Boolean isDirectory(PhpContext ctx, String path) {
        File file = file(ctx, path);
        return file == null ? null : file.isDirectory();
    }

    /** is_readable() on the host file system, or null when another file system must answer. */
    public static Boolean isReadable(PhpContext ctx, String path) {
        File file = file(ctx, path);
        if (file == null) {
            return null;
        }
        return directoryOnly(path) ? file.isDirectory() && file.canRead() : file.canRead();
    }

    /** is_writable() on the host file system, or null when another file system must answer. */
    public static Boolean isWritable(PhpContext ctx, String path) {
        File file = file(ctx, path);
        if (file == null) {
            return null;
        }
        return directoryOnly(path) ? file.isDirectory() && file.canWrite() : file.canWrite();
    }

    /** is_executable() on the host file system, or null when another file system must answer. */
    public static Boolean isExecutable(PhpContext ctx, String path) {
        File file = file(ctx, path);
        if (file == null) {
            return null;
        }
        return directoryOnly(path) ? file.isDirectory() && file.canExecute() : file.canExecute();
    }

    private static boolean directoryOnly(String path) {
        return !path.isEmpty() && path.charAt(path.length() - 1) == '/';
    }

    /**
     * The host {@link File} for a PHP path, resolved against the context's working directory
     * exactly as TruffleFile resolves it, or null when this context does not use the host
     * default file system (or the path cannot exist on the host, such as one with a NUL byte).
     */
    private static File file(PhpContext ctx, String path) {
        if (!ctx.usesHostFileSystem() || path.indexOf('\0') >= 0) {
            return null;
        }
        File file = new File(path);
        return file.isAbsolute() ? file : new File(ctx.env().getCurrentWorkingDirectory().getPath(), path);
    }

    private static Method method(Class<?> type, String name) {
        try {
            Method m = type.getDeclaredMethod(name);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Field field(String className, String name) {
        try {
            Field f = Class.forName(className).getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
