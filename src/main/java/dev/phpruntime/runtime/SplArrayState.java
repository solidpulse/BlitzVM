package dev.phpruntime.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.object.DynamicObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** ArrayObject/ArrayIterator storage holder, shared with iterators but copied on object clone. */
public final class SplArrayState implements PhpNativeState {
    public static final int STD_PROP_LIST = 1;
    public static final int ARRAY_AS_PROPS = 2;
    public static final int CHILD_ARRAYS_ONLY = 4;

    public static final class Storage {
        public PhpArray array;
        public PhpObject object;
        public long version;
        public int size;

        public Storage(Object source) {
            if (source instanceof PhpArray arr) {
                array = arr.share();
            } else if (source instanceof PhpObject obj) {
                if (obj.nativeState() instanceof SplArrayState state) {
                    array = state.arrayCopy();
                } else {
                    object = obj;
                }
            } else {
                throw new PhpError(PhpError.TYPE_ERROR, "Argument #1 ($array) must be of type array|object, "
                        + PhpOps.typeDescription(source) + " given", null);
            }
            size = array != null ? array.size() : PhpClass.visibleEntries(null, object, null).size();
        }
    }

    public Storage storage;
    public int flags;
    public int position;
    public String iteratorClass = "ArrayIterator";
    private Object[] keys;
    private Object[] iterationValues;
    private long keysVersion = -1;

    public SplArrayState(Object source, int flags) {
        storage = source instanceof PhpObject object && object.nativeState() instanceof SplArrayState state
                ? state.storage : new Storage(source);
        this.flags = flags;
    }

    public SplArrayState(Storage storage, int flags) {
        this.storage = storage;
        this.flags = flags;
    }

    @TruffleBoundary
    public PhpArray arrayCopy() {
        if (storage.array != null) {
            return storage.array.share();
        }
        PhpArray result = new PhpArray();
        for (var entry : PhpClass.visibleEntries(null, storage.object, null)) {
            result.put(entry.getKey(), PhpOps.copyOnAssign(entry.getValue()));
        }
        return result;
    }

    public Object get(Object key) {
        if (storage.array != null) { return storage.array.get(key); }
        Object raw = DynamicObject.GetNode.getUncached().execute(storage.object, PhpOps.toStr(key), null);
        return raw == null ? null : PhpClass.unwrapProp(raw);
    }

    public boolean exists(Object key) {
        return storage.array != null ? storage.array.containsKey(key)
                : DynamicObject.ContainsKeyNode.getUncached().execute(storage.object, PhpOps.toStr(key));
    }

    public void set(Object key, Object value) {
        if (storage.array != null) {
            storage.array = storage.array.forWrite();
            if (key == PhpNull.INSTANCE) { storage.array.append(PhpOps.copyOnAssign(value)); }
            else { storage.array.put(key, PhpOps.copyOnAssign(value)); }
        } else {
            DynamicObject.PutNode.getUncached().execute(storage.object, PhpOps.toStr(key), PhpOps.copyOnAssign(value));
        }
        storage.version++;
        storage.size = storage.array != null ? storage.array.size() : arrayCopy().size();
    }

    public PhpReference cell(Object key) {
        if (storage.array != null) {
            storage.array = storage.array.forWrite();
            PhpReference cell = key == PhpNull.INSTANCE ? storage.array.appendRef(new PhpReference(PhpNull.INSTANCE))
                    : storage.array.obtainElementRef(key);
            storage.version++;
            storage.size = storage.array.size();
            return cell;
        }
        String name = PhpOps.toStr(key);
        Object value = DynamicObject.GetNode.getUncached().execute(storage.object, name, PhpNull.INSTANCE);
        if (value instanceof PhpReference ref) { return ref; }
        PhpReference ref = new PhpReference(value);
        DynamicObject.PutNode.getUncached().execute(storage.object, name, ref);
        storage.version++;
        return ref;
    }

    public void unset(Object key) {
        if (storage.array != null) {
            storage.array = storage.array.forWrite();
            storage.array.remove(key);
        } else {
            DynamicObject.RemoveKeyNode.getUncached().execute(storage.object, PhpOps.toStr(key));
        }
        storage.version++;
        storage.size = storage.array != null ? storage.array.size() : arrayCopy().size();
    }

    public Object key() {
        if (keys == null || keysVersion != storage.version || storage.object != null) {
            refreshIteration();
        }
        return position >= 0 && position < keys.length ? keys[position] : PhpNull.INSTANCE;
    }

    public Object current() {
        if (keys == null || keysVersion != storage.version || storage.object != null) {
            refreshIteration();
        }
        if (position < 0 || position >= iterationValues.length) { return PhpNull.INSTANCE; }
        Object value = iterationValues[position];
        return value instanceof PhpReference ref ? ref.getValue() : value;
    }

    @TruffleBoundary
    private void refreshIteration() {
        PhpArray array = storage.array != null ? storage.array : arrayCopy();
        keys = array.keys().toArray();
        iterationValues = array.rawValues().toArray();
        keysVersion = storage.version;
    }

    public boolean valid() {
        return position >= 0 && position < count();
    }

    public long count() {
        return storage.array != null ? storage.size : arrayCopy().size();
    }

    @Override
    public PhpNativeState copy() {
        SplArrayState copy = storage.object != null ? new SplArrayState(storage.object, flags) : new SplArrayState(arrayCopy(), flags);
        copy.position = position;
        copy.iteratorClass = iteratorClass;
        return copy;
    }

    @Override
    public Map<String, Object> debugProperties(PhpObject self) {
        Map<String, Object> result = new LinkedHashMap<>();
        String owner = self.phpClass().hasSplInterface(SplInterfaces.AGGREGATE) ? "ArrayObject" : "ArrayIterator";
        result.put("\0" + owner + "\0storage", storage.object != null ? storage.object : arrayCopy());
        return result;
    }

    @Override
    public boolean supportsCompare() {
        return true;
    }

    @Override
    public int compareTo(PhpObject other) {
        if (!(other.nativeState() instanceof SplArrayState state)) { return 1; }
        PhpArray left = arrayCopy(), right = state.arrayCopy();
        int size = Integer.compare(left.size(), right.size());
        if (size != 0) { return size; }
        for (var entry : left.entries()) {
            if (!right.containsKey(entry.getKey())) { return 1; }
            int comparison = PhpOps.compare(entry.getValue(), right.get(entry.getKey()));
            if (comparison != 0) { return comparison; }
        }
        return 0;
    }
}
