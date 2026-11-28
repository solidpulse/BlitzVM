package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNativeState;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpStrings;
import dev.phpruntime.runtime.SplArrayState;
import dev.phpruntime.runtime.SplIterators;

import java.util.ArrayList;
import java.util.List;

/** SplFixedArray, SplDoublyLinkedList, SplQueue, SplStack and AppendIterator. */
public final class SplCollectionMethodRootNode extends RootNode {
    private final String owner;
    private final String method;

    public SplCollectionMethodRootNode(PhpLanguage language, String owner, String method) {
        super(language);
        this.owner = owner;
        this.method = method;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (!PhpStrings.CHECK) { return executeMethod(frame); }
        if (PhpStrings.CHECK) {
            PhpStrings.checkArguments(frame.getArguments(), owner + "::" + method);
        }
        Object result = executeMethod(frame);
        return PhpStrings.CHECK ? PhpStrings.checkReturn(result, owner + "::" + method) : result;
    }

    private Object executeMethod(VirtualFrame frame) {
        Object[] raw = frame.getArguments();
        PhpObject self = raw[0] instanceof PhpObject object ? object : null;
        int n = raw.length - 2;
        PhpContext ctx = PhpContext.get(this);
        Object a = n > 0 ? raw[2] : PhpNull.INSTANCE;
        Object b = n > 1 ? raw[3] : PhpNull.INSTANCE;

        if (owner.equals("SplFixedArray")) {
            if (method.equals("fromArray")) {
                if (!(a instanceof PhpArray array)) {
                    throw new PhpError(PhpError.TYPE_ERROR, "SplFixedArray::fromArray(): Argument #1 ($array)"
                            + " must be of type array, " + PhpOps.typeDescription(a) + " given", this);
                }
                PhpObject created = new PhpObject(ctx.lookupClass("SplFixedArray"), ctx.nextObjectId());
                List<Object> values = new ArrayList<>();
                for (var entry : array.entries()) {
                    values.add(PhpOps.copyOnAssign(entry.getValue()));
                }
                created.setNativeState(new FixedState(PhpArray.ofList(values), values.size(), 0));
                return created;
            }
            if (self == null) {
                throw new PhpError("Non-static method SplFixedArray::" + method + "() cannot be called statically", this);
            }
            if (method.equals("__construct")) {
                long size = n > 0 ? PhpOps.toLong(a) : 0;
                if (size < 0) {
                    throw new PhpError(PhpError.VALUE_ERROR,
                            "SplFixedArray::__construct(): Argument #1 ($size) must be greater than or equal to 0", this);
                }
                PhpArray values = new PhpArray();
                for (long i = 0; i < size; i++) {
                    values.append(PhpNull.INSTANCE);
                }
                self.setNativeState(new FixedState(values, (int) size, 0));
                return PhpNull.INSTANCE;
            }
            FixedState state = fixed(self);
            return switch (method) {
                case "count", "getSize" -> (long) state.size();
                case "toArray" -> state.values();
                case "jsonSerialize" -> state.values();
                case "setSize" -> {
                    int size = (int) PhpOps.toLong(a);
                    if (size < 0) {
                        throw new PhpError(PhpError.VALUE_ERROR,
                                "SplFixedArray::setSize(): Argument #1 ($size) must be greater than or equal to 0", this);
                    }
                    PhpArray values = state.values().forWrite();
                    while (values.size() > size) {
                        values.removeLast();
                    }
                    while (values.size() < size) {
                        values.append(PhpNull.INSTANCE);
                    }
                    self.setNativeState(new FixedState(values, size, Math.min(state.position(), size)));
                    yield PhpNull.INSTANCE;
                }
                case "offsetExists" -> {
                    long index = PhpOps.toLong(a);
                    yield index >= 0 && index < state.size() && state.values().get(index) != PhpNull.INSTANCE;
                }
                case "offsetGet" -> {
                    long index = PhpOps.toLong(a);
                    if (index < 0 || index >= state.size()) {
                        throw new PhpError("OutOfBoundsException", "Index invalid or out of range", this);
                    }
                    yield nullValue(state.values().get(index));
                }
                case "offsetSet" -> {
                    long index = PhpOps.toLong(a);
                    if (index < 0 || index >= state.size()) {
                        throw new PhpError("OutOfBoundsException", "Index invalid or out of range", this);
                    }
                    PhpArray values = state.values().forWrite();
                    values.put(index, PhpOps.copyOnAssign(b));
                    self.setNativeState(new FixedState(values, state.size(), state.position()));
                    yield PhpNull.INSTANCE;
                }
                case "offsetUnset" -> {
                    long index = PhpOps.toLong(a);
                    if (index < 0 || index >= state.size()) {
                        throw new PhpError("OutOfBoundsException", "Index invalid or out of range", this);
                    }
                    PhpArray values = state.values().forWrite();
                    values.put(index, PhpNull.INSTANCE);
                    self.setNativeState(new FixedState(values, state.size(), state.position()));
                    yield PhpNull.INSTANCE;
                }
                case "getIterator" -> {
                    PhpObject iterator = new PhpObject(ctx.lookupClass("ArrayIterator"), ctx.nextObjectId());
                    ctx.lookupClass("ArrayIterator").populateDefaults(iterator);
                    iterator.setNativeState(new SplArrayState(state.values(), 0));
                    yield iterator;
                }
                case "rewind" -> { self.setNativeState(state.withPosition(0)); yield PhpNull.INSTANCE; }
                case "valid" -> state.position() < state.size();
                case "current" -> state.position() < state.size()
                        ? nullValue(state.values().get(state.position())) : PhpNull.INSTANCE;
                case "key" -> (long) state.position();
                case "next" -> {
                    if (state.position() < state.size()) {
                        self.setNativeState(state.withPosition(state.position() + 1));
                    }
                    yield PhpNull.INSTANCE;
                }
                default -> throw new IllegalStateException("SplFixedArray::" + method);
            };
        }

        if (owner.equals("AppendIterator")) {
            AppendState state;
            if (method.equals("__construct")) {
                state = new AppendState(new ArrayList<>(), 0, 0);
                self.setNativeState(state);
                return PhpNull.INSTANCE;
            }
            state = append(self);
            return switch (method) {
                case "append" -> {
                    if (!(a instanceof PhpObject iterator)
                            || !dev.phpruntime.runtime.SplInterfaces.has(iterator, dev.phpruntime.runtime.SplInterfaces.TRAVERSABLE)) {
                        throw new PhpError(PhpError.TYPE_ERROR, "AppendIterator::append(): Argument #1 ($iterator)"
                                + " must be of type Iterator, " + PhpOps.typeDescription(a) + " given", this);
                    }
                    state.iterators().add(SplIterators.resolve(iterator));
                    SplIterators.call(SplIterators.resolve(iterator), "rewind");
                    yield PhpNull.INSTANCE;
                }
                case "rewind" -> {
                    state.index = 0;
                    state.position = 0;
                    if (!state.iterators().isEmpty()) {
                        SplIterators.call(state.iterators().get(0), "rewind");
                    }
                    advance(state);
                    yield PhpNull.INSTANCE;
                }
                case "valid" -> state.position < state.iterators().size();
                case "current" -> state.position < state.iterators().size()
                        ? SplIterators.call(state.iterators().get(state.position), "current") : PhpNull.INSTANCE;
                case "key" -> state.position < state.iterators().size()
                        ? SplIterators.call(state.iterators().get(state.position), "key") : PhpNull.INSTANCE;
                case "next" -> {
                    if (state.position < state.iterators().size()) {
                        PhpObject iterator = state.iterators().get(state.position);
                        SplIterators.call(iterator, "next");
                        advance(state);
                    }
                    yield PhpNull.INSTANCE;
                }
                case "getIteratorIndex" -> state.position < state.iterators().size()
                        ? (long) state.position : PhpNull.INSTANCE;
                case "getArrayIterator" -> {
                    PhpArray array = new PhpArray();
                    state.iterators().forEach(array::append);
                    PhpObject iterator = new PhpObject(ctx.lookupClass("ArrayIterator"), ctx.nextObjectId());
                    ctx.lookupClass("ArrayIterator").populateDefaults(iterator);
                    iterator.setNativeState(new SplArrayState(array, 0));
                    yield iterator;
                }
                case "getInnerIterator" -> state.position < state.iterators().size()
                        ? state.iterators().get(state.position) : PhpNull.INSTANCE;
                default -> throw new IllegalStateException("AppendIterator::" + method);
            };
        }

        // SplDoublyLinkedList, SplQueue and SplStack.
        ListState state;
        if (method.equals("__construct")) {
            self.setNativeState(new ListState(new ArrayList<>(), 0, owner.equals("SplStack") ? 2 : 0));
            return PhpNull.INSTANCE;
        }
        state = list(self);
        return switch (method) {
            case "push", "enqueue" -> {
                state.values().add(PhpOps.copyOnAssign(a));
                yield PhpNull.INSTANCE;
            }
            case "dequeue", "shift" -> state.values().isEmpty() ? PhpNull.INSTANCE : state.values().remove(0);
            case "pop" -> state.values().isEmpty() ? PhpNull.INSTANCE : state.values().remove(state.values().size() - 1);
            case "unshift" -> {
                state.values().add(0, PhpOps.copyOnAssign(a));
                yield PhpNull.INSTANCE;
            }
            case "top" -> state.values().isEmpty() ? PhpNull.INSTANCE : state.values().get(state.values().size() - 1);
            case "bottom" -> state.values().isEmpty() ? PhpNull.INSTANCE : state.values().get(0);
            case "isEmpty" -> state.values().isEmpty();
            case "count" -> (long) state.values().size();
            case "add" -> {
                long index = PhpOps.toLong(a);
                if (index < 0 || index > state.values().size()) {
                    throw new PhpError("OutOfRangeException", "Offset invalid or out of range", this);
                }
                state.values().add((int) index, PhpOps.copyOnAssign(b));
                yield PhpNull.INSTANCE;
            }
            case "offsetExists" -> {
                long index = PhpOps.toLong(a);
                yield index >= 0 && index < state.values().size();
            }
            case "offsetGet" -> {
                long index = PhpOps.toLong(a);
                if (index < 0 || index >= state.values().size()) {
                    throw new PhpError("OutOfRangeException", "Offset invalid or out of range", this);
                }
                yield state.values().get((int) index);
            }
            case "offsetSet" -> {
                long index = PhpOps.toLong(a);
                if (index < 0 || index >= state.values().size()) {
                    throw new PhpError("OutOfRangeException", "Offset invalid or out of range", this);
                }
                state.values().set((int) index, PhpOps.copyOnAssign(b));
                yield PhpNull.INSTANCE;
            }
            case "offsetUnset" -> {
                long index = PhpOps.toLong(a);
                if (index < 0 || index >= state.values().size()) {
                    throw new PhpError("OutOfRangeException", "Offset invalid or out of range", this);
                }
                state.values().remove((int) index);
                yield PhpNull.INSTANCE;
            }
            case "setIteratorMode" -> {
                state.mode = (int) PhpOps.toLong(a);
                yield PhpNull.INSTANCE;
            }
            case "getIteratorMode" -> (long) state.mode;
            case "rewind" -> { state.position = state.mode == 2 ? state.values().size() - 1 : 0; yield PhpNull.INSTANCE; }
            case "valid" -> state.position >= 0 && state.position < state.values().size();
            case "current" -> state.position >= 0 && state.position < state.values().size()
                    ? state.values().get(state.position) : PhpNull.INSTANCE;
            case "key" -> (long) state.position;
            case "next" -> {
                state.position += (state.mode & 2) != 0 ? -1 : 1;
                yield PhpNull.INSTANCE;
            }
            case "prev" -> {
                state.position -= 1;
                yield PhpNull.INSTANCE;
            }
            case "serialize" -> {
                PhpArray out = new PhpArray();
                state.values().forEach(out::append);
                yield dev.phpruntime.builtins.Builtins.lookup("serialize").impl().call(ctx, new Object[]{out});
            }
            case "__serialize" -> {
                PhpArray out = new PhpArray();
                state.values().forEach(v -> out.append(PhpOps.copyOnAssign(v)));
                yield out;
            }
            case "__unserialize" -> {
                if (!(a instanceof PhpArray data)) {
                    throw new PhpError(PhpError.TYPE_ERROR, "SplDoublyLinkedList::__unserialize(): Argument #1 ($data)"
                            + " must be of type array, " + PhpOps.typeDescription(a) + " given", this);
                }
                List<Object> values = new ArrayList<>();
                for (var entry : data.entries()) {
                    values.add(entry.getValue());
                }
                self.setNativeState(new ListState(values, 0, 0));
                yield PhpNull.INSTANCE;
            }
            default -> throw new IllegalStateException(owner + "::" + method);
        };
    }

    private static Object nullValue(Object value) {
        return value == null ? PhpNull.INSTANCE : value;
    }

    private static void advance(AppendState state) {
        while (state.position < state.iterators().size()
                && !PhpOps.toBool(SplIterators.call(state.iterators().get(state.position), "valid"))) {
            state.position++;
            if (state.position < state.iterators().size()) {
                SplIterators.call(state.iterators().get(state.position), "rewind");
            }
        }
    }

    private record FixedState(PhpArray values, int size, int position) implements PhpNativeState {
        @Override
        public PhpNativeState copy() {
            return new FixedState(values.share(), size, position);
        }

        FixedState withPosition(int newPosition) {
            return new FixedState(values, size, newPosition);
        }
    }

    private static final class AppendState implements PhpNativeState {
        final List<PhpObject> iterators;
        int index;
        int position;

        AppendState(List<PhpObject> iterators, int index, int position) {
            this.iterators = iterators;
            this.index = index;
            this.position = position;
        }

        List<PhpObject> iterators() {
            return iterators;
        }

        @Override
        public PhpNativeState copy() {
            return new AppendState(new ArrayList<>(iterators), index, position);
        }
    }

    private static final class ListState implements PhpNativeState {
        final List<Object> values;
        int position;
        int mode;

        ListState(List<Object> values, int position, int mode) {
            this.values = values;
            this.position = position;
            this.mode = mode;
        }

        List<Object> values() {
            return values;
        }

        @Override
        public PhpNativeState copy() {
            return new ListState(new ArrayList<>(values), position, mode);
        }
    }

    private static FixedState fixed(PhpObject object) {
        if (object.nativeState() instanceof FixedState state) {
            return state;
        }
        throw new PhpError("SplFixedArray object is not initialized");
    }

    private static AppendState append(PhpObject object) {
        if (object.nativeState() instanceof AppendState state) {
            return state;
        }
        throw new PhpError("AppendIterator object is not initialized");
    }

    private static ListState list(PhpObject object) {
        if (object.nativeState() instanceof ListState state) {
            return state;
        }
        throw new PhpError("SplDoublyLinkedList object is not initialized");
    }
}
