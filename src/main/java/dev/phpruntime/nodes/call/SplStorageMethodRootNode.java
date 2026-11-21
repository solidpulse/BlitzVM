package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.runtime.*;

public final class SplStorageMethodRootNode extends RootNode {
    private final String name;
    @Child private ObjectMethodNode hashMethod;
    private final int hashId;

    public SplStorageMethodRootNode(PhpLanguage language, String name) {
        super(language);
        this.name = name;
        hashMethod = new ObjectMethodNode(language, "getHash");
        hashId = language.methodId("getHash");
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (!PhpStrings.CHECK) { return executeMethod(frame); }
        PhpStrings.checkArguments(frame.getArguments(), "SPL storage method");
        return PhpStrings.checkReturn(executeMethod(frame), "SPL storage method");
    }

    private Object executeMethod(VirtualFrame frame) {
        Object[] args = frame.getArguments();
        PhpObject self = (PhpObject) args[0];
        if (self.nativeState() == null) { self.setNativeState(new SplObjectStorageState()); }
        SplObjectStorageState state = (SplObjectStorageState) self.nativeState();
        return switch (name) {
            case "__construct" -> PhpNull.INSTANCE;
            case "attach", "offsetSet" -> {
                state.attach(key(self, object(args), state), args.length > 3 ? args[3] : PhpNull.INSTANCE);
                yield PhpNull.INSTANCE;
            }
            case "detach", "offsetUnset" -> { state.detach(key(self, object(args), state)); yield PhpNull.INSTANCE; }
            case "contains", "offsetExists" -> state.objects.containsKey(key(self, object(args), state));
            case "offsetGet" -> {
                PhpObject key = key(self, object(args), state);
                if (!state.objects.containsKey(key)) { throw new PhpError("UnexpectedValueException", "Object not found", this); }
                yield PhpOps.copyOnAssign(state.objects.get(key));
            }
            case "count" -> (long) state.objects.size();
            case "rewind" -> { state.position = 0; yield PhpNull.INSTANCE; }
            case "next" -> { state.position++; yield PhpNull.INSTANCE; }
            case "valid" -> state.current() != null;
            case "current" -> state.current() != null ? state.current() : PhpNull.INSTANCE;
            case "key" -> (long) state.position;
            case "getInfo" -> state.current() != null ? PhpOps.copyOnAssign(state.objects.get(state.current())) : PhpNull.INSTANCE;
            case "setInfo" -> {
                if (state.current() != null) { state.attach(state.current(), args[2]); }
                yield PhpNull.INSTANCE;
            }
            case "getHash" -> String.format("%032x", object(args).id());
            case "serialize" -> {
                if (!state.objects.isEmpty()) {
                    com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                    throw new PhpError("SplObjectStorage::serialize() of object entries is not supported", this);
                }
                yield "x:i:0;m:" + dev.phpruntime.builtins.Builtins.lookup("serialize").impl().call(
                        dev.phpruntime.PhpContext.get(this), new Object[]{dev.phpruntime.builtins.SplNativeSupport.properties(self)});
            }
            case "unserialize" -> {
                String data = PhpOps.toStr(args[2]);
                if (!data.startsWith("x:i:0;m:")) {
                    com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                    throw new PhpError("UnexpectedValueException", "Error at offset 0 of " + data.length() + " bytes", this);
                }
                state.clear();
                Object props = dev.phpruntime.builtins.Builtins.lookup("unserialize").impl().call(
                        dev.phpruntime.PhpContext.get(this), new Object[]{data.substring(8)});
                if (props instanceof PhpArray array) {
                    for (var entry : array.entries()) {
                        com.oracle.truffle.api.object.DynamicObject.PutNode.getUncached().execute(self,
                                PhpOps.toStr(entry.getKey()), PhpOps.copyOnAssign(entry.getValue()));
                    }
                }
                yield PhpNull.INSTANCE;
            }
            case "addAll", "removeAll", "removeAllExcept" -> {
                if (!(args[2] instanceof PhpObject other) || !(other.nativeState() instanceof SplObjectStorageState source)) {
                    com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                    throw new PhpError(PhpError.TYPE_ERROR, "SplObjectStorage::" + name + "(): Argument #1 ($storage) must be of type SplObjectStorage", this);
                }
                if (name.equals("addAll")) {
                    for (var entry : source.objects.entrySet()) { state.attach(entry.getKey(), entry.getValue()); }
                } else {
                    for (PhpObject key : new java.util.ArrayList<>(state.objects.keySet())) {
                        if (source.objects.containsKey(key) != name.equals("removeAllExcept")) { state.detach(key); }
                    }
                }
                yield (long) state.objects.size();
            }
            default -> throw new IllegalStateException(name);
        };
    }

    private PhpObject object(Object[] args) {
        if (args.length > 2 && args[2] instanceof PhpObject object) { return object; }
        com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
        throw new PhpError(PhpError.TYPE_ERROR, "SplObjectStorage::" + name + "(): Argument #1 ($object) must be of type object, "
                + (args.length > 2 ? PhpOps.typeDescription(args[2]) : "null") + " given", this);
    }

    private PhpObject key(PhpObject self, PhpObject object, SplObjectStorageState state) {
        if (self.phpClass().methodById(hashId).callTarget().getRootNode() instanceof SplStorageMethodRootNode) {
            return object;
        }
        String hash = PhpOps.toStr(CallArgHelper.unwrapReturn(hashMethod.call(self, object)));
        for (PhpObject existing : state.objects.keySet()) {
            String existingHash = PhpOps.toStr(CallArgHelper.unwrapReturn(hashMethod.call(self, existing)));
            if (existingHash.equals(hash)) { return existing; }
        }
        return object;
    }

    @Override public String getName() { return "SplObjectStorage::" + name; }

    /** A builtin method is not guest code: tools treat it as internal. */
    @Override public boolean isInternal() { return true; }
}
