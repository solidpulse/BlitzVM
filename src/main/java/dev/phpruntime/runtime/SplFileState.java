package dev.phpruntime.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** File info plus a directory cursor. Local page-cache I/O never releases the request's CPU slot. */
public final class SplFileState implements PhpNativeState {
    public static final int CURRENT_AS_SELF = 16;
    public static final int CURRENT_AS_PATHNAME = 32;
    public static final int KEY_AS_FILENAME = 256;
    public static final int SKIP_DOTS = 4096;
    public static final int FOLLOW_SYMLINKS = 16384;
    public String pathname;
    public String directory;
    public String subPath = "";
    public List<String> entries;
    public int position;
    public int flags;
    public String infoClass = "SplFileInfo";
    public String fileClass = "SplFileObject";

    public SplFileState(String pathname) {
        this.pathname = trimPath(pathname);
    }

    @TruffleBoundary
    public static SplFileState directory(String pathname, int flags) {
        if (pathname.isEmpty()) {
            throw new PhpError("ValueError", "DirectoryIterator::__construct(): Argument #1 ($directory) cannot be empty", null);
        }
        SplFileState state = new SplFileState(pathname);
        state.directory = state.pathname;
        state.flags = flags;
        state.entries = new ArrayList<>();
        if ((flags & SKIP_DOTS) == 0) {
            state.entries.add(".");
            state.entries.add("..");
        }
        try (var stream = Files.newDirectoryStream(Path.of(state.directory))) {
            for (Path entry : stream) {
                state.entries.add(entry.getFileName().toString());
            }
        } catch (IOException error) {
            throw new PhpError("UnexpectedValueException", "RecursiveDirectoryIterator::__construct(" + pathname
                    + "): Failed to open directory: " + (Files.exists(Path.of(pathname)) ? "Not a directory" : "No such file or directory"), null);
        }
        state.refreshPath();
        return state;
    }

    public boolean valid() {
        return entries != null && position >= 0 && position < entries.size();
    }

    public String filename() {
        if (entries != null) {
            return valid() ? entries.get(position) : "";
        }
        int slash = pathname.lastIndexOf('/');
        return slash >= 0 ? pathname.substring(slash + 1) : pathname;
    }

    public String path() {
        int slash = pathname.lastIndexOf('/');
        return slash >= 0 ? pathname.substring(0, slash) : "";
    }

    public void refreshPath() {
        if (entries != null) {
            pathname = valid() ? directory + (directory.endsWith("/") ? "" : "/") + entries.get(position) : "";
        }
    }

    public boolean isDot() {
        return filename().equals(".") || filename().equals("..");
    }

    @TruffleBoundary
    public boolean hasChildren(boolean allowLinks) {
        return valid() && !isDot() && Files.isDirectory(Path.of(pathname))
                && (allowLinks || (flags & FOLLOW_SYMLINKS) != 0 || !Files.isSymbolicLink(Path.of(pathname)));
    }

    public String subPathname() {
        return subPath.isEmpty() ? filename() : subPath + "/" + filename();
    }

    private static String trimPath(String path) {
        int end = path.length();
        while (end > 1 && path.charAt(end - 1) == '/') { end--; }
        return path.substring(0, end);
    }

    @Override
    public PhpNativeState copy() {
        SplFileState copy = new SplFileState(pathname);
        copy.directory = directory;
        copy.entries = entries == null ? null : new ArrayList<>(entries);
        copy.position = position;
        copy.flags = flags;
        copy.subPath = subPath;
        copy.infoClass = infoClass;
        copy.fileClass = fileClass;
        return copy;
    }

    @Override
    public Map<String, Object> debugProperties(PhpObject self) {
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("\0SplFileInfo\0pathName", pathname);
        properties.put("\0SplFileInfo\0fileName", filename());
        return properties;
    }
}
