package dev.phpruntime.snapshot;

import com.oracle.truffle.api.RootCallTarget;
import dev.phpruntime.builtins.Builtins;
import dev.phpruntime.builtins.ContextStateSnapshot;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpClosure;
import dev.phpruntime.runtime.PhpFunction;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The immutable result of a capture. It holds no request objects from the template run: every
 * mutable object, array and reference cell is represented by a descriptor that a restore copies
 * into its own context. Pure arrays are the one exception: they are frozen at capture and shared,
 * exactly like constant array literals.
 */
public final class Snapshot {

    /** A global symbol: a language id, or a dynamic name, and the cell it points at. */
    record Global(String name, int id, boolean dynamic, RefNode cell) {
    }

    /** A reference cell. Identity is preserved: two globals bound together share one RefNode. */
    static final class RefNode {
        Object value;
        boolean aliased;
        CheckNode check;
    }

    /** The parameters of a typed property's {@code CellCheck}, for a restored context. */
    record CheckNode(dev.phpruntime.runtime.DeclaredType type, boolean strict, PhpClass selfClass,
                     String owner, String property) {
    }

    /** An object with its property storage, closure payload and supported native state. */
    static final class ObjectNode {
        final PhpClass cls;
        final long id;
        Object[] keys;
        Object[] values;
        ClosureNode closure;
        Object nativeState;

        ObjectNode(PhpClass cls, long id) {
            this.cls = cls;
            this.id = id;
        }
    }

    /** An array that held an object or a reference anywhere inside, copied again per restore. */
    static final class ArrayNode {
        Object[] keys;
        Object[] values;
    }

    /** A closure payload; call targets and classes are engine-wide and stay shared. */
    static final class ClosureNode {
        PhpClosure.Kind kind;
        RootCallTarget callTarget;
        Builtins.Def builtin;
        Object boundThis;
        PhpClass calledClass;
        PhpClass traitSelf;
        Object[] captures;
        String magicMethodName;
    }

    /** A closed resource. Open resources refuse capture, so no handle is ever carried. */
    record ResourceNode(long id, String type, boolean closed) {
    }

    /** An ArrayObject/ArrayIterator storage; its array or object is mapped through the copier. */
    record NativeArrayNode(Object array, Object object, int flags, int position, String iteratorClass,
                           long version, int size) {
    }

    record ClassStateNode(PhpClass cls, PhpClass.RequestState.StateSnapshot state) {
    }

    record StaticEntry(Object owner, Map<String, Object> cells) {
    }

    record ShutdownEntry(Object callable, Object[] args) {
    }

    record ErrorHandlerEntry(Object callable, long errorLevels) {
    }

    /** Marks static locals owned by the request context itself (file-scope static variables). */
    enum ContextOwner {
        INSTANCE
    }

    final List<Global> globals;
    final Map<String, Object> constants;
    final List<PhpFunction> functions;
    final Map<String, PhpClass> classes;
    final List<ClassStateNode> classStates;
    final Set<String> includedFiles;
    final List<Object> autoloaders;
    final List<ShutdownEntry> shutdownFunctions;
    final List<ErrorHandlerEntry> errorHandlers;
    final List<Object> exceptionHandlers;
    final List<StaticEntry> staticLocals;
    final ContextStateSnapshot contextState;
    final long nextObjectId;
    final long nextAnonymousId;

    Snapshot(List<Global> globals, Map<String, Object> constants, List<PhpFunction> functions,
             Map<String, PhpClass> classes, List<ClassStateNode> classStates, Set<String> includedFiles,
             List<Object> autoloaders, List<ShutdownEntry> shutdownFunctions,
             List<ErrorHandlerEntry> errorHandlers, List<Object> exceptionHandlers,
             List<StaticEntry> staticLocals, ContextStateSnapshot contextState,
             long nextObjectId, long nextAnonymousId) {
        this.globals = List.copyOf(globals);
        this.constants = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(constants));
        this.functions = List.copyOf(functions);
        this.classes = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(classes));
        this.classStates = List.copyOf(classStates);
        this.includedFiles = Set.copyOf(includedFiles);
        this.autoloaders = List.copyOf(autoloaders);
        this.shutdownFunctions = List.copyOf(shutdownFunctions);
        this.errorHandlers = List.copyOf(errorHandlers);
        this.exceptionHandlers = List.copyOf(exceptionHandlers);
        this.staticLocals = List.copyOf(staticLocals);
        this.contextState = contextState;
        this.nextObjectId = nextObjectId;
        this.nextAnonymousId = nextAnonymousId;
    }

    /** For the report and the harness: the size of the captured graph. */
    public int globalCount() {
        return globals.size();
    }

    public int classCount() {
        return classes.size();
    }

    public int functionCount() {
        return functions.size();
    }

    public int includedFileCount() {
        return includedFiles.size();
    }

    /** The canonical paths of the files boot included; the server checks their modification times. */
    public Set<String> includedFiles() {
        return includedFiles;
    }
}
