package dev.phpruntime.nodes.call;

import dev.phpruntime.runtime.PhpStrings;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.runtime.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Filesystem SPL methods. The boundary excludes only local I/O, not user iterator overrides. */
public final class SplFileMethodRootNode extends RootNode {
    private final String owner;
    private final String name;

    public SplFileMethodRootNode(PhpLanguage language, String owner, String name) {
        super(language);
        this.owner = owner;
        this.name = name;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        PhpStrings.checkArguments(frame.getArguments(), owner + "::" + name);
        return PhpStrings.checkReturn(invoke(PhpContext.get(this), frame.getArguments()), owner + "::" + name);
    }

    @TruffleBoundary
    private Object invoke(PhpContext ctx, Object[] args) {
        PhpObject self = (PhpObject) args[0];
        if (name.equals("__construct")) {
            if (args.length < 3) { throw new PhpError(PhpError.ARGUMENT_COUNT, owner + "::__construct() expects at least 1 argument, 0 given", this); }
            String path = PhpOps.toStr(args[2]);
            if (path.indexOf('\0') >= 0) {
                throw new PhpError(PhpError.VALUE_ERROR, owner + "::__construct(): Argument #1 ($filename) must not contain any null bytes", this);
            }
            if (owner.equals("SplFileInfo")) {
                self.setNativeState(new SplFileState(path));
            } else {
                int defaultFlags = owner.equals("FilesystemIterator") ? SplFileState.SKIP_DOTS : 0;
                int flags = args.length > 3 ? (int) PhpOps.toLong(args[3]) : defaultFlags;
                try {
                    self.setNativeState(SplFileState.directory(path, flags));
                } catch (PhpError error) {
                    throw new PhpError(error.kind(), error.detail().replace("RecursiveDirectoryIterator::__construct", owner + "::__construct"), this);
                }
            }
            return PhpNull.INSTANCE;
        }
        if (!(self.nativeState() instanceof SplFileState state)) {
            throw new PhpError("The parent constructor was not called: the object is in an invalid state", this);
        }
        Path path = Path.of(state.pathname.isEmpty() ? "." : PhpStrings.toJava(state.pathname));
        try {
            return switch (name) {
                case "rewind" -> { state.position = 0; state.refreshPath(); yield PhpNull.INSTANCE; }
                case "next" -> { state.position++; state.refreshPath(); yield PhpNull.INSTANCE; }
                case "valid" -> state.valid();
                case "key" -> owner.equals("DirectoryIterator") ? (long) state.position
                        : (state.flags & SplFileState.KEY_AS_FILENAME) != 0 ? state.filename() : state.pathname;
                case "current" -> {
                    if (!state.valid()) { yield PhpNull.INSTANCE; }
                    if (owner.equals("DirectoryIterator") || (state.flags & 240) == SplFileState.CURRENT_AS_SELF) { yield self; }
                    if ((state.flags & 240) == SplFileState.CURRENT_AS_PATHNAME) { yield state.pathname; }
                    yield info(ctx, state.infoClass, state.pathname);
                }
                case "seek" -> {
                    long position = PhpOps.toLong(args[2]);
                    if (position < 0 || position >= state.entries.size()) {
                        throw new PhpError("OutOfBoundsException", "Seek position " + position + " is out of range", this);
                    }
                    state.position = (int) position;
                    state.refreshPath();
                    yield PhpNull.INSTANCE;
                }
                case "getFlags" -> (long) state.flags;
                case "setFlags" -> { state.flags = (int) PhpOps.toLong(args[2]); yield PhpNull.INSTANCE; }
                case "isDot" -> state.isDot();
                case "getFilename" -> state.filename();
                case "getPathname" -> state.pathname;
                case "getPath" -> state.path();
                case "getBasename" -> {
                    String filename = state.filename();
                    String suffix = args.length > 2 ? PhpOps.toStr(args[2]) : "";
                    yield !suffix.isEmpty() && filename.endsWith(suffix) ? filename.substring(0, filename.length() - suffix.length()) : filename;
                }
                case "getExtension" -> {
                    String filename = state.filename();
                    int dot = filename.lastIndexOf('.');
                    yield dot < 0 ? "" : filename.substring(dot + 1);
                }
                case "__toString" -> owner.equals("DirectoryIterator") ? state.filename() : state.pathname;
                case "isDir" -> Files.isDirectory(path);
                case "isFile" -> Files.isRegularFile(path);
                case "isLink" -> Files.isSymbolicLink(path);
                case "isReadable" -> Files.isReadable(path);
                case "isWritable" -> Files.isWritable(path);
                case "isExecutable" -> Files.isExecutable(path);
                case "getRealPath" -> Files.exists(path) ? PhpStrings.fromJava(path.toRealPath().toString()) : false;
                case "getSize" -> Files.size(path);
                case "getMTime" -> Files.getLastModifiedTime(path).toMillis() / 1000;
                case "getATime" -> ((java.nio.file.attribute.FileTime) Files.getAttribute(path, "basic:lastAccessTime")).toMillis() / 1000;
                case "getCTime" -> ((java.nio.file.attribute.FileTime) Files.getAttribute(path, "unix:ctime")).toMillis() / 1000;
                case "getInode" -> ((Number) Files.getAttribute(path, "unix:ino")).longValue();
                case "getOwner" -> ((Number) Files.getAttribute(path, "unix:uid")).longValue();
                case "getGroup" -> ((Number) Files.getAttribute(path, "unix:gid")).longValue();
                case "getPerms" -> ((Number) Files.getAttribute(path, "unix:mode")).longValue();
                case "getType" -> Files.isSymbolicLink(path) ? "link" : Files.isDirectory(path) ? "dir" : Files.isRegularFile(path) ? "file" : "unknown";
                case "getLinkTarget" -> PhpStrings.fromJava(Files.readSymbolicLink(path).toString());
                case "getFileInfo" -> info(ctx, args.length > 2 && args[2] != PhpNull.INSTANCE ? PhpOps.toStr(args[2]) : state.infoClass, state.pathname);
                case "getPathInfo" -> state.path().isEmpty() ? PhpNull.INSTANCE
                        : info(ctx, args.length > 2 && args[2] != PhpNull.INSTANCE ? PhpOps.toStr(args[2]) : state.infoClass, state.path());
                case "setInfoClass" -> { state.infoClass = args.length > 2 ? PhpOps.toStr(args[2]) : "SplFileInfo"; yield PhpNull.INSTANCE; }
                case "setFileClass" -> { state.fileClass = args.length > 2 ? PhpOps.toStr(args[2]) : "SplFileObject"; yield PhpNull.INSTANCE; }
                case "hasChildren" -> state.hasChildren(args.length > 2 && PhpOps.toBool(args[2]));
                case "getChildren" -> {
                    SplFileState child = SplFileState.directory(state.pathname, state.flags);
                    child.subPath = state.subPathname();
                    child.infoClass = state.infoClass;
                    child.fileClass = state.fileClass;
                    PhpObject iterator = new PhpObject(self.phpClass(), ctx.nextObjectId());
                    self.phpClass().populateDefaults(iterator);
                    iterator.setNativeState(child);
                    yield iterator;
                }
                case "getSubPath" -> state.subPath;
                case "getSubPathname" -> state.subPathname();
                default -> throw new IllegalStateException(name);
            };
        } catch (IOException error) {
            throw new PhpError("RuntimeException", owner + "::" + name + "(): stat failed for " + state.pathname, this);
        }
    }

    private PhpObject info(PhpContext ctx, String name, String path) {
        PhpClass cls = ctx.lookupOrAutoloadClass(name);
        if (cls == null) { throw new PhpError("Class \"" + name + "\" not found", this); }
        PhpObject info = new PhpObject(cls, ctx.nextObjectId());
        cls.populateDefaults(info);
        info.setNativeState(new SplFileState(path));
        return info;
    }

    @Override public String getName() { return owner + "::" + name; }
}
