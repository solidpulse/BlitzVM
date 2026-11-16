package dev.phpruntime.builtins;

import com.oracle.truffle.api.object.DynamicObject;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpClassTemplate;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNativeState;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpResource;
import dev.phpruntime.runtime.Visibility;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** dir() and the built-in Directory class. */
public final class DirClasses {

    private DirClasses() {
    }

    private static final class DirectoryState implements PhpNativeState {
        final PhpStream.DirHandle handle;
        boolean closed;

        DirectoryState(PhpStream.DirHandle handle) {
            this.handle = handle;
        }

        @Override
        public PhpNativeState copy() {
            return new DirectoryState(handle);
        }

        @Override
        public Map<String, Object> debugProperties(PhpObject self) {
            return null;
        }
    }

    static void register() {
        Builtins.def("dir", 1, 2, (ctx, a) -> {
            String path = PhpOps.toStr(a[0]);
            PhpResource r = StreamFunctions.openDir(ctx, path, "dir");
            if (r == null) {
                return false;
            }
            PhpClass cls = ctx.lookupClass("Directory");
            PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
            obj.setNativeState(new DirectoryState((PhpStream.DirHandle) r.handle()));
            DynamicObject.PutNode put = DynamicObject.PutNode.getUncached();
            put.execute(obj, "path", path);
            put.execute(obj, "handle", r);
            return obj;
        });
    }

    public static void buildTemplates(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        addMethod(language, methods, "read", (ctx, self, args) -> {
            DirectoryState state = openState(self, "read");
            PhpStream.DirHandle d = state.handle;
            return d.index < d.names.size() ? d.names.get(d.index++) : false;
        });
        addMethod(language, methods, "rewind", (ctx, self, args) -> {
            openState(self, "rewind");
            state(self).handle.index = 0;
            return PhpNull.INSTANCE;
        });
        addMethod(language, methods, "close", (ctx, self, args) -> {
            DirectoryState state = state(self);
            Object handle = DynamicObject.GetNode.getUncached().execute(self, "handle", PhpNull.INSTANCE);
            if (handle instanceof PhpResource r) {
                r.close();
            }
            state.closed = true;
            return PhpNull.INSTANCE;
        });
        out.put("directory", new PhpClassTemplate(language.nextTemplateId(), "Directory", false, false, false,
                false, null, List.of(), List.of(), List.of(), methods));
    }

    private static DirectoryState openState(PhpObject self, String method) {
        DirectoryState state = state(self);
        if (state.closed) {
            throw new PhpError(PhpError.TYPE_ERROR,
                    "Directory::" + method + "(): cannot use Directory resource after it has been closed", null);
        }
        return state;
    }

    private static DirectoryState state(PhpObject self) {
        if (self != null && self.nativeState() instanceof DirectoryState state) {
            return state;
        }
        throw new PhpError("Directory object is not initialized", null);
    }

    private static void addMethod(PhpLanguage language, Map<String, PhpClassTemplate.MethodDecl> map,
                                  String name, dev.phpruntime.runtime.PhpExceptions.BuiltinMethod impl) {
        String lower = name.toLowerCase();
        var root = new dev.phpruntime.runtime.PhpExceptions.BuiltinMethodRootNode(language, name, impl);
        map.put(lower, new PhpClassTemplate.MethodDecl(
                name, lower, language.methodId(name), Visibility.PUBLIC, false, false, false, root.getCallTarget()));
    }
}
