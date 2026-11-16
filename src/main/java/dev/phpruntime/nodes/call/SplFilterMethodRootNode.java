package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.builtins.Builtins;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.SplInterfaces;
import dev.phpruntime.runtime.SplIterators;

/**
 * FilterIterator, RegexIterator and RecursiveRegexIterator methods (php's spl_dual_it): the
 * current element is fetched from the inner iterator and kept while {@code accept()} (called
 * through normal method dispatch, so user subclasses override it) returns true. These wrap
 * directory listings and similar; they are not hot paths, so the logic stays behind a boundary.
 */
public final class SplFilterMethodRootNode extends RootNode {

    public static final int USE_KEY = 1;
    public static final int INVERT_MATCH = 2;
    public static final int MATCH = 0;
    public static final int GET_MATCH = 1;
    public static final int ALL_MATCHES = 2;
    public static final int SPLIT = 3;
    public static final int REPLACE = 4;

    /** Per-object state; request-local, attached to the PhpObject. */
    public static final class State implements dev.phpruntime.runtime.PhpNativeState {
        PhpObject inner;
        Object current;
        Object key;
        boolean valid;
        String regex;
        int mode;
        int flags;
        int pregFlags;

        @Override
        public dev.phpruntime.runtime.PhpNativeState copy() {
            State s = new State();
            s.inner = inner;
            s.current = current;
            s.key = key;
            s.valid = valid;
            s.regex = regex;
            s.mode = mode;
            s.flags = flags;
            s.pregFlags = pregFlags;
            return s;
        }
    }

    private final String owner;
    private final String name;

    public SplFilterMethodRootNode(PhpLanguage language, String owner, String name) {
        super(language);
        this.owner = owner;
        this.name = name;
    }

    @Override
    public String getName() {
        return owner + "::" + name;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return run(frame.getArguments());
    }

    @TruffleBoundary
    private Object run(Object[] args) {
        PhpObject self = (PhpObject) args[0];
        if (name.equals("__construct")) {
            construct(self, args);
            return PhpNull.INSTANCE;
        }
        if (!(self.nativeState() instanceof State s)) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError("The parent constructor was not called: the object is in an invalid state", this);
        }
        return switch (name) {
            case "rewind" -> {
                SplIterators.call(s.inner, "rewind");
                fetch(self, s);
                yield PhpNull.INSTANCE;
            }
            case "next" -> {
                SplIterators.call(s.inner, "next");
                fetch(self, s);
                yield PhpNull.INSTANCE;
            }
            case "valid" -> s.valid;
            case "current" -> s.valid ? s.current : PhpNull.INSTANCE;
            case "key" -> s.valid ? s.key : PhpNull.INSTANCE;
            case "getInnerIterator" -> s.inner;
            case "accept" -> accept(self, s);
            case "getMode" -> (long) s.mode;
            case "setMode" -> {
                long m = PhpOps.toLong(args[2]);
                if (m < MATCH || m > REPLACE) {
                    com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                    throw new PhpError(PhpError.VALUE_ERROR,
                            "RegexIterator::setMode(): Argument #1 ($mode) must be RegexIterator::MATCH, RegexIterator::GET_MATCH, "
                                    + "RegexIterator::ALL_MATCHES, RegexIterator::SPLIT, or RegexIterator::REPLACE", this);
                }
                s.mode = (int) m;
                yield PhpNull.INSTANCE;
            }
            case "getFlags" -> (long) s.flags;
            case "setFlags" -> {
                s.flags = (int) PhpOps.toLong(args[2]);
                yield PhpNull.INSTANCE;
            }
            case "getPregFlags" -> (long) s.pregFlags;
            case "setPregFlags" -> {
                s.pregFlags = (int) PhpOps.toLong(args[2]);
                yield PhpNull.INSTANCE;
            }
            case "getRegex" -> s.regex;
            case "hasChildren" -> SplIterators.call(s.inner, "hasChildren");
            case "getChildren" -> {
                Object children = SplIterators.call(s.inner, "getChildren");
                PhpObject child = new PhpObject(self.phpClass(), PhpContext.get(this).nextObjectId());
                self.phpClass().populateDefaults(child);
                construct(child, new Object[]{child, child.phpClass(), children, s.regex, (long) s.mode, (long) s.flags, (long) s.pregFlags});
                yield child;
            }
            default -> throw new PhpError("Call to undefined method " + owner + "::" + name + "()", this);
        };
    }

    private void construct(PhpObject self, Object[] args) {
        if (args.length < 3 || !(args[2] instanceof PhpObject it) || !SplInterfaces.has(it, SplInterfaces.TRAVERSABLE)) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError(PhpError.TYPE_ERROR, owner + "::__construct(): Argument #1 ($iterator) must be of type "
                    + (owner.equals("RecursiveRegexIterator") ? "RecursiveIterator" : "Iterator"), this);
        }
        State s = new State();
        s.inner = SplIterators.resolve(it);
        if (!owner.equals("FilterIterator")) {
            if (args.length < 4) {
                com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                throw new PhpError(PhpError.ARGUMENT_COUNT, owner + "::__construct() expects at least 2 arguments, 1 given", this);
            }
            s.regex = PhpOps.toStr(args[3]);
            s.mode = args.length > 4 ? (int) PhpOps.toLong(args[4]) : MATCH;
            s.flags = args.length > 5 ? (int) PhpOps.toLong(args[5]) : 0;
            s.pregFlags = args.length > 6 ? (int) PhpOps.toLong(args[6]) : 0;
        }
        self.setNativeState(s);
    }

    /** spl_dual_it fetch: advance the inner iterator until accept() is true. */
    private void fetch(PhpObject self, State s) {
        while (PhpOps.toBool(SplIterators.call(s.inner, "valid"))) {
            s.current = SplIterators.call(s.inner, "current");
            s.key = SplIterators.call(s.inner, "key");
            s.valid = true;
            if (PhpOps.toBool(SplIterators.call(self, "accept"))) {
                return;
            }
            SplIterators.call(s.inner, "next");
        }
        s.valid = false;
        s.current = PhpNull.INSTANCE;
        s.key = PhpNull.INSTANCE;
    }

    /** RegexIterator::accept (FilterIterator::accept is abstract and never reaches here). */
    private boolean accept(PhpObject self, State s) {
        if (!s.valid) {
            return false;
        }
        boolean useKey = (s.flags & USE_KEY) != 0;
        Object subjectValue = useKey ? s.key : s.current;
        if (subjectValue instanceof PhpArray) {
            return false;
        }
        String subject = PhpOps.toStr(subjectValue);
        PhpContext ctx = PhpContext.get(this);
        boolean result;
        switch (s.mode) {
            case GET_MATCH, ALL_MATCHES -> {
                Object[] a = {s.regex, subject, PhpNull.INSTANCE, (long) s.pregFlags};
                Object count = Builtins.lookup(s.mode == GET_MATCH ? "preg_match" : "preg_match_all").impl().call(ctx, a);
                s.current = a[2];
                result = PhpOps.toLong(count) > 0;
            }
            case SPLIT -> {
                Object parts = Builtins.lookup("preg_split").impl().call(ctx, new Object[]{s.regex, subject, -1L, (long) s.pregFlags});
                result = parts instanceof PhpArray arr && arr.size() > 1;
                if (result) {
                    s.current = parts;
                }
            }
            case REPLACE -> {
                Object replacement = com.oracle.truffle.api.object.DynamicObject.GetNode.getUncached()
                        .execute(self, "replacement", PhpNull.INSTANCE);
                Object[] a = {s.regex, replacement == PhpNull.INSTANCE ? "" : PhpOps.toStr(replacement), subject, -1L, 0L};
                Object replaced = Builtins.lookup("preg_replace").impl().call(ctx, a);
                result = PhpOps.toLong(a[4]) > 0;
                if (result) {
                    if (useKey) {
                        s.key = replaced;
                    } else {
                        s.current = replaced;
                    }
                }
            }
            default -> result = PhpOps.toLong(Builtins.lookup("preg_match").impl().call(ctx, new Object[]{s.regex, subject})) > 0;
        }
        return (s.flags & INVERT_MATCH) != 0 ? !result : result;
    }
}
