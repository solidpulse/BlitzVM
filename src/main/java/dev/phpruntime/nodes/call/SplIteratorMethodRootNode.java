package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.stmt.IteratorNode;
import dev.phpruntime.runtime.*;

/** Wrapper traversal is lazy and uses cached PHP method dispatch, including child overrides. */
public final class SplIteratorMethodRootNode extends RootNode {
    private final String owner;
    private final String name;
    @Child private IteratorNode protocol;
    @Child private ObjectMethodNode hasChildren;
    @Child private ObjectMethodNode getChildren;
    private final int hasChildrenId;

    public SplIteratorMethodRootNode(PhpLanguage language, String owner, String name) {
        super(language);
        this.owner = owner;
        this.name = name;
        protocol = new IteratorNode(language);
        hasChildren = new ObjectMethodNode(language, "hasChildren");
        getChildren = new ObjectMethodNode(language, "getChildren");
        hasChildrenId = language.methodId("hasChildren");
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (!PhpStrings.CHECK) { return executeMethod(frame); }
        if (PhpStrings.CHECK) {
            PhpStrings.checkArguments(frame.getArguments(), "SPL iterator method");
        }
        Object result = executeMethod(frame);
        return PhpStrings.CHECK ? PhpStrings.checkReturn(result, "SPL iterator method") : result;
    }

    private Object executeMethod(VirtualFrame frame) {
        Object[] args = frame.getArguments();
        PhpObject self = (PhpObject) args[0];
        if (name.equals("__construct")) {
            if (args.length < 3 || !(args[2] instanceof PhpObject obj) || !SplInterfaces.has(obj, SplInterfaces.TRAVERSABLE)) {
                com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                throw new PhpError(PhpError.TYPE_ERROR, owner + "::__construct(): Argument #1 ($iterator) must be of type Traversable", this);
            }
            PhpObject inner = protocol.resolve((PhpObject) args[2]);
            boolean recursive = owner.equals("RecursiveIteratorIterator");
            if (recursive && inner.phpClass().methodById(hasChildrenId) == null) {
                com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                throw new PhpError("InvalidArgumentException", "An instance of RecursiveIterator or IteratorAggregate creating it is required", this);
            }
            int mode = recursive && args.length > 3 ? (int) PhpOps.toLong(args[3]) : 0;
            int flags = recursive && args.length > 4 ? (int) PhpOps.toLong(args[4]) : 0;
            SplIteratorState state = new SplIteratorState(inner, recursive, mode, flags);
            if (recursive) {
                state.levels.add(new SplIteratorState.Level(inner));
                state.currentDepth = protocol.valid(inner) ? 0 : -1;
            }
            self.setNativeState(state);
            return PhpNull.INSTANCE;
        }
        if (!(self.nativeState() instanceof SplIteratorState state)) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError("The parent constructor was not called: the object is in an invalid state", this);
        }
        return switch (name) {
            case "getInnerIterator" -> state.currentIterator();
            case "rewind" -> {
                protocol.rewind(state.inner);
                if (state.recursive) {
                    state.levels.clear();
                    state.levels.add(new SplIteratorState.Level(state.inner));
                    state.currentDepth = -1;
                    advance(state);
                }
                yield PhpNull.INSTANCE;
            }
            case "valid" -> state.recursive ? state.currentDepth >= 0 : protocol.valid(state.inner);
            case "current" -> state.recursive && state.currentDepth < 0 ? PhpNull.INSTANCE : protocol.current(state.currentIterator());
            case "key" -> state.recursive && state.currentDepth < 0 ? PhpNull.INSTANCE : protocol.key(state.currentIterator());
            case "next" -> {
                if (state.recursive) { advance(state); }
                else { protocol.next(state.inner); }
                yield PhpNull.INSTANCE;
            }
            case "getDepth" -> (long) Math.max(0, state.currentDepth);
            case "setMaxDepth" -> {
                int depth = args.length > 2 ? (int) PhpOps.toLong(args[2]) : -1;
                if (depth < -1) { throw new PhpError("OutOfRangeException", "Parameter maxDepth must be >= -1", this); }
                state.maxDepth = depth;
                yield PhpNull.INSTANCE;
            }
            case "getMaxDepth" -> state.maxDepth < 0 ? false : (long) state.maxDepth;
            case "getSubIterator" -> {
                int depth = args.length > 2 && args[2] != PhpNull.INSTANCE ? (int) PhpOps.toLong(args[2]) : state.currentDepth;
                yield depth >= 0 && depth < state.levels.size() ? state.levels.get(depth).iterator : PhpNull.INSTANCE;
            }
            case "callHasChildren" -> hasChild(state.currentIterator());
            case "callGetChildren" -> CallArgHelper.unwrapReturn(getChildren.call(state.currentIterator()));
            case "beginIteration", "endIteration", "beginChildren", "endChildren", "nextElement" -> PhpNull.INSTANCE;
            default -> throw new IllegalStateException(name);
        };
    }

    private boolean hasChild(PhpObject iterator) {
        return dev.phpruntime.nodes.expr.CondNode.toBool(CallArgHelper.unwrapReturn(hasChildren.call(iterator)));
    }

    private void advance(SplIteratorState state) {
        state.currentDepth = -1;
        while (!state.levels.isEmpty()) {
            TruffleSafepoint.poll(this);
            int depth = state.levels.size() - 1;
            SplIteratorState.Level level = state.levels.get(depth);
            PhpObject it = level.iterator;
            if (!protocol.valid(it)) {
                state.levels.remove(depth);
                continue;
            }
            if (level.phase == 0) {
                boolean children = hasChild(it);
                boolean descend = children && (state.maxDepth < 0 || depth < state.maxDepth);
                level.phase = descend ? 1 : 3;
                if (!children || state.mode == 1 || (!descend && state.mode == 2)) {
                    state.currentDepth = depth;
                    return;
                }
            }
            if (level.phase == 1) {
                level.phase = 2;
                try {
                    Object child = CallArgHelper.unwrapReturn(getChildren.call(it));
                    if (!(child instanceof PhpObject obj)) {
                        com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                        throw new PhpError("UnexpectedValueException", "Objects returned by RecursiveIterator::getChildren() must implement RecursiveIterator", this);
                    }
                    PhpObject childIterator = protocol.resolve(obj);
                    protocol.rewind(childIterator);
                    state.levels.add(new SplIteratorState.Level(childIterator));
                    continue;
                } catch (PhpException | PhpError error) {
                    if ((state.flags & 16) == 0) { throw error; }
                }
            }
            if (level.phase == 2) {
                level.phase = 3;
                if (state.mode == 2) {
                    state.currentDepth = depth;
                    return;
                }
            }
            protocol.next(it);
            level.phase = 0;
        }
    }

    @Override public String getName() { return owner + "::" + name; }

    /** A builtin method is not guest code: tools treat it as internal. */
    @Override public boolean isInternal() { return true; }
}
