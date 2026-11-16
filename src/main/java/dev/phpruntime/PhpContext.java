package dev.phpruntime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.builtins.Builtins;
import dev.phpruntime.nodes.ControlFlow;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpCallable;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpClassTemplate;
import dev.phpruntime.runtime.PhpClosure;
import dev.phpruntime.runtime.PhpConstants;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpFunction;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;
import dev.phpruntime.runtime.PhpRequest;
import dev.phpruntime.runtime.PhpStrings;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Per-execution state: the global symbol table, the function table and the output
 * buffer. One polyglot Context (and therefore one PhpContext) is created per HTTP
 * request, which gives PHP's shared-nothing request model for free.
 */
public final class PhpContext {

    private static final ContextReference<PhpContext> REFERENCE = ContextReference.create(PhpLanguage.class);
    private static final int FLUSH_THRESHOLD = 8192;

    private final PhpLanguage language;
    private final TruffleLanguage.Env env;
    private PhpReference[] globalsById;
    private final Map<String, PhpReference> dynamicGlobals = new java.util.LinkedHashMap<>();
    private final Map<String, Integer> globalOrder = new java.util.LinkedHashMap<>();
    private final Set<String> reservedGlobals = new java.util.HashSet<>();
    private final Map<String, PhpFunction> functions = new HashMap<>();
    private PhpFunction[] functionsById = new PhpFunction[256];
    private final Map<String, PhpClass> classes = new HashMap<>();
    private PhpClass[] classesById = new PhpClass[128];
    private final Map<String, Object> constants = new java.util.LinkedHashMap<>();
    private final Set<String> includedFiles = new HashSet<>();
    private long nextObjectId = 1;
    private long nextAnonymousId = 1;
    private final PhpClass stdClass;
    private final PhpClass closureClass;
    private record ShutdownEntry(Object callable, Object[] args) {
    }

    private record ErrorHandlerEntry(Object callable, long errorLevels) {
    }

    private final List<Object> autoloaders = new ArrayList<>();
    private final Set<String> autoloadingInProgress = new HashSet<>();
    private final List<ShutdownEntry> shutdownFunctions = new ArrayList<>();
    private final Deque<ErrorHandlerEntry> errorHandlers = new ArrayDeque<>();
    private final Deque<Object> exceptionHandlers = new ArrayDeque<>();
    private final IdentityHashMap<Object, Map<String, PhpReference>> staticLocals = new IdentityHashMap<>();
    private boolean inErrorHandler;
    private final StringBuilder output = new StringBuilder();
    private final PrintStream err;
    private PhpRequest request;
    /** Per-request state owned by builtins (strtok position, default timezone); see builtins.ContextState. */
    private Object builtinState;
    private Node currentBuiltinCallSite;
    private String byteStringOrigin;

    PhpContext(PhpLanguage language, TruffleLanguage.Env env) {
        this.language = language;
        this.env = env;
        this.globalsById = new PhpReference[Math.max(32, language.globalIdCount() + 8)];
        this.err = new PrintStream(env.err(), true, StandardCharsets.ISO_8859_1);
        this.stdClass = declareClass(language.stdClassTemplate());
        this.closureClass = declareClass(language.closureTemplate());
    }

    public PhpClass stdClass() {
        return stdClass;
    }

    public PhpClass closureClass() {
        return closureClass;
    }

    public PhpObject newClosure(PhpClosure closure) {
        return new PhpObject(closureClass, nextObjectId(), closure);
    }

    public static PhpContext get(Node node) {
        return REFERENCE.get(node);
    }

    public TruffleLanguage.Env env() {
        return env;
    }

    public PhpLanguage language() {
        return language;
    }

    /** The request this execution serves; a CLI run gets an empty request. */
    public PhpRequest request() {
        if (request == null) {
            request = PhpRequest.current();
        }
        return request;
    }

    public Object builtinState() {
        return builtinState;
    }

    public void setBuiltinState(Object state) {
        builtinState = state;
    }

    public Node currentBuiltinCallSite() {
        return currentBuiltinCallSite;
    }

    public void setCurrentBuiltinCallSite(Node site) {
        currentBuiltinCallSite = site;
    }

    public String byteStringOrigin() {
        return byteStringOrigin;
    }

    public void setByteStringOrigin(String origin) {
        byteStringOrigin = origin;
    }

    // ------------------------------------------------------------ globals

    @TruffleBoundary
    public void reserveGlobals(String[] names) {
        for (String name : names) {
            globalOrder.putIfAbsent(name, language.globalId(name));
            reservedGlobals.add(name);
        }
    }

    public boolean isReservedGlobal(String name) { return reservedGlobals.contains(name); }

    @TruffleBoundary
    public void initializeGlobalOrder() {
        globalOrder.clear();
        for (String name : new String[]{"argv", "argc", "_GET", "_POST", "_COOKIE", "_FILES", "_SERVER"}) {
            Integer id = language.lookupGlobalId(name);
            if (id != null && readGlobalById(id) != null) { globalOrder.put(name, id); }
        }
    }

    @TruffleBoundary
    private void exposeAutoGlobal(String name) {
        if (name.equals("_ENV") || name.equals("_REQUEST")) {
            Integer id = language.lookupGlobalId(name);
            if (id != null) { globalOrder.putIfAbsent(name, id); }
        }
    }

    public void exposeAutoGlobalById(int id) { exposeAutoGlobal(language.globalName(id)); }

    /** extract can create an ordinary symbol called GLOBALS; literal $GLOBALS remains special. */
    @TruffleBoundary
    public void bindExtractedGlobal(String name, PhpReference cell) {
        if (name.equals("GLOBALS")) { dynamicGlobals.put(name, cell.alias()); }
        else { bindGlobal(name, cell); }
    }

    @TruffleBoundary
    public void writeExtractedGlobal(String name, Object value) {
        if (name.equals("GLOBALS")) {
            PhpReference cell = dynamicGlobals.get(name);
            if (cell == null) { dynamicGlobals.put(name, PhpReference.local(value)); }
            else { cell.setValue(value); }
        } else { writeGlobal(name, value); }
    }

    @TruffleBoundary
    public Object readGlobalSymbol(String name) {
        if (name.equals("GLOBALS")) {
            PhpReference cell = dynamicGlobals.get(name);
            return cell == null ? null : cell.getValue();
        }
        return readGlobal(name);
    }

    @TruffleBoundary
    public PhpReference globalSymbolRef(String name) {
        if (!name.equals("GLOBALS")) { return globalRef(name); }
        return dynamicGlobals.computeIfAbsent(name, n -> new PhpReference(PhpNull.INSTANCE));
    }

    @TruffleBoundary
    public void unsetGlobalSymbol(String name) {
        if (name.equals("GLOBALS")) { dynamicGlobals.remove(name); }
        else { unsetGlobal(name); }
    }

    public Object readGlobalById(int id) {
        PhpReference[] table = globalsById;
        if (id < table.length) {
            PhpReference cell = table[id];
            return cell == null ? null : cell.getValue();
        }
        return null;
    }

    public void writeGlobalById(int id, Object value) {
        PhpReference[] table = globalsById;
        if (id < table.length) {
            PhpReference cell = table[id];
            if (cell != null) {
                cell.setValue(value);
                return;
            }
        }
        createGlobalCellById(id, value);
    }

    public String appendGlobalById(int id, String suffix, boolean resultNeeded) {
        PhpReference[] table = globalsById;
        PhpReference cell = id < table.length ? table[id] : null;
        if (cell == null) { cell = createGlobalCellById(id, null); }
        return cell.appendString(suffix, resultNeeded);
    }

    @TruffleBoundary
    private PhpReference createGlobalCellById(int id, Object value) {
        if (id >= globalsById.length) {
            globalsById = Arrays.copyOf(globalsById, Math.max(id + 1, globalsById.length * 2));
        }
        PhpReference cell = globalsById[id];
        if (cell == null) {
            cell = PhpReference.local(value);
            globalsById[id] = cell;
            globalOrder.put(language.globalName(id), id);
        } else {
            cell.setValue(value);
        }
        return cell;
    }

    public PhpReference globalRefById(int id) {
        PhpReference[] table = globalsById;
        if (id < table.length) {
            PhpReference cell = table[id];
            if (cell != null) {
                cell.ensureDefined();
                return cell.alias();
            }
        }
        return createGlobalCellById(id, PhpNull.INSTANCE).alias();
    }

    public void bindGlobalById(int id, PhpReference cell) {
        if (id >= globalsById.length) {
            growGlobalsTable(id);
        }
        if (globalsById[id] == null) { recordGlobal(id); }
        globalsById[id] = cell.alias();
    }

    @TruffleBoundary
    private void recordGlobal(int id) { globalOrder.put(language.globalName(id), id); }

    @TruffleBoundary
    private void growGlobalsTable(int id) {
        globalsById = Arrays.copyOf(globalsById, Math.max(id + 1, globalsById.length * 2));
    }

    public void unsetGlobalById(int id) {
        PhpReference[] table = globalsById;
        if (id < table.length) {
            table[id] = null;
            forgetGlobal(id);
        }
    }

    @TruffleBoundary
    private void forgetGlobal(int id) {
        String name = language.globalName(id);
        if (!reservedGlobals.contains(name)) { globalOrder.remove(name); }
    }

    @TruffleBoundary
    public Object readGlobal(String name) {
        if ("GLOBALS".equals(name)) {
            return buildGlobalsSnapshot();
        }
        Integer id = language.lookupGlobalId(name);
        if (id != null) {
            exposeAutoGlobal(name);
            return readGlobalById(id);
        }
        PhpReference cell = dynamicGlobals.get(name);
        return cell == null ? null : cell.getValue();
    }

    @TruffleBoundary
    public void writeGlobal(String name, Object value) {
        if ("GLOBALS".equals(name)) {
            throw PhpError.fatal("$GLOBALS can only be modified using the $GLOBALS[$name] = $value syntax");
        }
        writeGlobalById(language.globalId(name), value);
    }

    @TruffleBoundary
    public boolean hasGlobal(String name) {
        Object v = readGlobal(name);
        return v != null && v != PhpNull.INSTANCE;
    }

    @TruffleBoundary
    public boolean isGlobalDefined(String name) {
        if ("GLOBALS".equals(name)) {
            return false;
        }
        Integer id = language.lookupGlobalId(name);
        if (id != null) {
            PhpReference[] table = globalsById;
            return id < table.length && table[id] != null && table[id].getValue() != null;
        }
        PhpReference cell = dynamicGlobals.get(name);
        return cell != null && cell.getValue() != null;
    }

    @TruffleBoundary
    public PhpReference globalRef(String name) {
        if ("GLOBALS".equals(name)) {
            throw PhpError.fatal("Cannot acquire reference to $GLOBALS");
        }
        Integer id = language.lookupGlobalId(name);
        if (id != null) {
            return globalRefById(id);
        }
        PhpReference cell = dynamicGlobals.get(name);
        if (cell != null) {
            cell.ensureDefined();
            return cell;
        }
        return globalRefById(language.globalId(name));
    }

    @TruffleBoundary
    public void bindGlobal(String name, PhpReference cell) {
        if ("GLOBALS".equals(name)) {
            throw PhpError.fatal("$GLOBALS can only be modified using the $GLOBALS[$name] = $value syntax");
        }
        bindGlobalById(language.globalId(name), cell);
    }

    @TruffleBoundary
    public PhpArray buildGlobalsSnapshot() {
        PhpArray snap = new PhpArray();
        for (var entry : globalOrder.entrySet()) {
            int i = entry.getValue();
            PhpReference cell = i < globalsById.length ? globalsById[i] : null;
            if (cell != null && cell.getValue() != null) {
                String name = entry.getKey();
                if (name != null && !"GLOBALS".equals(name)) {
                    if (cell.isAliased()) { snap.putRef(name, cell); }
                    else { snap.put(name, PhpOps.copyOnAssign(cell.getValue())); }
                }
            }
        }
        for (Map.Entry<String, PhpReference> e : dynamicGlobals.entrySet()) {
            PhpReference cell = e.getValue();
            if (cell != null && cell.getValue() != null && !"GLOBALS".equals(e.getKey())) {
                if (cell.isAliased()) { snap.putRef(e.getKey(), cell); }
                else { snap.put(e.getKey(), PhpOps.copyOnAssign(cell.getValue())); }
            }
        }
        return snap;
    }

    @TruffleBoundary
    public PhpReference getStaticLocal(Object ownerKey, String name) {
        Map<String, PhpReference> map = staticLocals.get(ownerKey);
        return map == null ? null : map.get(name);
    }

    @TruffleBoundary
    public PhpReference initStaticLocal(Object ownerKey, String name, Object initialValue) {
        Map<String, PhpReference> map = staticLocals.computeIfAbsent(ownerKey, k -> new HashMap<>());
        PhpReference ref = map.get(name);
        if (ref == null) {
            ref = new PhpReference(initialValue);
            map.put(name, ref);
        }
        return ref;
    }

    @TruffleBoundary
    public void copyClosureStatics(PhpObject original, PhpObject copy, boolean share) {
        Map<String, PhpReference> values = staticLocals.get(original);
        if (values == null) { return; }
        if (share) {
            staticLocals.put(copy, values);
        } else {
            Map<String, PhpReference> cloned = new HashMap<>();
            for (var entry : values.entrySet()) {
                cloned.put(entry.getKey(), new PhpReference(PhpOps.copyOnAssign(entry.getValue().getValue())));
            }
            staticLocals.put(copy, cloned);
        }
    }

    @TruffleBoundary
    public void unsetGlobal(String name) {
        if ("GLOBALS".equals(name)) {
            throw PhpError.fatal("$GLOBALS can only be modified using the $GLOBALS[$name] = $value syntax");
        }
        Integer id = language.lookupGlobalId(name);
        if (id != null) {
            unsetGlobalById(id);
        } else {
            dynamicGlobals.remove(name);
        }
    }

    // ------------------------------------------------------------ functions

    @TruffleBoundary
    public PhpFunction lookupFunction(String name) {
        String n = name.startsWith("\\") ? name.substring(1) : name;
        return functions.get(PhpStrings.lower(n));
    }

    @TruffleBoundary
    public void declareFunction(PhpFunction fn) {
        if (Builtins.lookup(fn.name()) != null || functions.putIfAbsent(PhpStrings.lower(fn.name()), fn) != null) {
            throw PhpError.fatal("Cannot redeclare function " + fn.name() + "()");
        }
        int id = language.functionId(fn.name());
        if (id >= functionsById.length) {
            functionsById = java.util.Arrays.copyOf(functionsById, Math.max(id + 1, functionsById.length * 2));
        }
        functionsById[id] = fn;
    }

    /** The function with this {@link PhpLanguage#functionId id} in this context, or null. Cheap enough for every call. */
    public PhpFunction functionById(int id) {
        PhpFunction[] table = functionsById;
        return id < table.length ? table[id] : null;
    }

    /** Names of script-declared functions (lowercase keys), for get_defined_functions(). */
    @TruffleBoundary
    public List<String> definedFunctionNames() {
        return new ArrayList<>(functions.keySet());
    }

    // ------------------------------------------------------------ classes

    public PhpClass classById(int id) {
        PhpClass[] table = classesById;
        if (id < table.length) {
            PhpClass cls = table[id];
            if (cls != null) {
                return cls;
            }
        }
        return resolveBuiltinById(id);
    }

    @TruffleBoundary
    private PhpClass resolveBuiltinById(int id) {
        PhpClassTemplate tpl = language.builtinClassTemplateById(id);
        return tpl == null ? null : lookupClass(tpl.lowerName());
    }

    @TruffleBoundary
    public PhpClass lookupClass(String name) {
        String n = name.startsWith("\\") ? name.substring(1) : name;
        String lower = PhpStrings.lower(n);
        PhpClass existing = classes.get(lower);
        if (existing != null) {
            return existing;
        }
        PhpClassTemplate builtin = language.builtinClassTemplate(lower);
        if (builtin != null) {
            return declareClassInternal(builtin, true);
        }
        return null;
    }

    /** class_alias(): makes an existing class reachable under another name in this request. */
    @TruffleBoundary
    public boolean aliasClass(String alias, PhpClass cls) {
        String n = alias.startsWith("\\") ? alias.substring(1) : alias;
        String lower = PhpStrings.lower(n);
        if (classes.containsKey(lower) || language.builtinClassTemplate(lower) != null) {
            return false;
        }
        classes.put(lower, cls);
        int id = language.classId(lower);
        if (id >= classesById.length) {
            classesById = Arrays.copyOf(classesById, Math.max(id + 1, classesById.length * 2));
        }
        classesById[id] = cls;
        return true;
    }

    /** Constants defined with define() or const in this request, in definition order. */
    public Map<String, Object> userConstants() {
        return java.util.Collections.unmodifiableMap(constants);
    }

    @TruffleBoundary
    public PhpClass lookupOrAutoloadClass(String name) {
        String n = name.startsWith("\\") ? name.substring(1) : name;
        PhpClass cls = lookupClass(n);
        String lower = PhpStrings.lower(n);
        if (cls != null || autoloaders.isEmpty() || !autoloadingInProgress.add(lower)) {
            return cls;
        }
        try {
            for (Object cbVal : List.copyOf(autoloaders)) {
                PhpCallable cb = PhpCallable.resolveOrNull(this, cbVal);
                if (cb != null) {
                    cb.call(this, null, null, n);
                    cls = lookupClass(n);
                    if (cls != null) {
                        return cls;
                    }
                }
            }
            return null;
        } finally {
            autoloadingInProgress.remove(lower);
        }
    }

    @TruffleBoundary
    public boolean registerAutoload(Object callable, boolean prepend) {
        for (Object existing : autoloaders) {
            if (sameCallback(existing, callable)) {
                return true;
            }
        }
        if (prepend) {
            autoloaders.add(0, callable);
        } else {
            autoloaders.add(callable);
        }
        return true;
    }

    @TruffleBoundary
    public boolean unregisterAutoload(Object callable) {
        for (int i = 0; i < autoloaders.size(); i++) {
            if (sameCallback(autoloaders.get(i), callable)) {
                autoloaders.remove(i);
                return true;
            }
        }
        return false;
    }

    @TruffleBoundary
    public PhpArray autoloadFunctions() {
        PhpArray out = new PhpArray();
        for (Object cb : autoloaders) {
            out.append(PhpOps.copyOnAssign(cb));
        }
        return out;
    }

    private static boolean sameCallback(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a instanceof String sa && b instanceof String sb) {
            return PhpStrings.lower(sa).equals(PhpStrings.lower(sb));
        }
        if (a instanceof PhpArray aa && b instanceof PhpArray ab) {
            return PhpOps.strictEquals(aa, ab);
        }
        return Objects.equals(a, b);
    }

    @TruffleBoundary
    public boolean canHoistClass(PhpClassTemplate template) {
        if (template.parentName() != null && lookupClass(template.parentName()) == null) {
            return false;
        }
        for (String iface : template.interfaceNames()) {
            if (lookupClass(iface) == null) {
                return false;
            }
        }
        for (PhpClassTemplate.TraitUse use : template.traitUses()) {
            for (String tn : use.traitNames()) {
                if (lookupClass(tn) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    @TruffleBoundary
    public PhpClass declareClass(PhpClassTemplate template) {
        return declareClassInternal(template, false);
    }

    @TruffleBoundary
    private PhpClass declareClassInternal(PhpClassTemplate template, boolean isBuiltin) {
        String lower = template.lowerName();
        if (classes.containsKey(lower) || (!isBuiltin && language.builtinClassTemplate(lower) != null)) {
            String kind = template.isInterface() ? "interface" : (template.isTrait() ? "trait" : "class");
            throw PhpError.fatal("Cannot declare " + kind + " " + template.name() + ", because the name is already in use");
        }
        PhpClass parent = null;
        if (template.parentName() != null) {
            parent = lookupOrAutoloadClass(template.parentName());
            if (parent == null) {
                String kind = template.isInterface() ? "Interface" : "Class";
                throw new PhpError(kind + " \"" + template.parentName() + "\" not found");
            }
            if (!template.isInterface() && (parent.isInterface() || parent.isTrait())) {
                throw PhpError.fatal(template.name() + " cannot extend "
                        + (parent.isTrait() ? "trait " : "interface ") + parent.name());
            }
            if (!template.isInterface() && parent.isFinal()) {
                throw PhpError.fatal("Class " + template.name() + " cannot extend final class " + parent.name());
            }
        }
        List<PhpClass> ifaces = new ArrayList<>(template.interfaceNames().size());
        for (String ifaceName : template.interfaceNames()) {
            PhpClass iface = lookupOrAutoloadClass(ifaceName);
            if (iface == null) {
                throw new PhpError("Interface \"" + ifaceName + "\" not found");
            }
            if (!iface.isInterface()) {
                throw PhpError.fatal(template.name() + " cannot implement " + iface.name() + " - it is not an interface");
            }
            if (!isBuiltin && !template.isInterface() && "throwable".equals(iface.template().lowerName())
                    && !dev.phpruntime.runtime.PhpExceptions.isThrowable(this, parent)) {
                throw PhpError.fatal("Class " + template.name() + " cannot implement interface Throwable, extend Exception or Error instead");
            }
            ifaces.add(iface);
        }
        Map<String, PhpClass> resolvedTraits = template.traitUses().isEmpty() ? Map.of() : new HashMap<>();
        for (PhpClassTemplate.TraitUse use : template.traitUses()) {
            for (String traitName : use.traitNames()) {
                String tLower = PhpStrings.lower(traitName);
                if (!resolvedTraits.containsKey(tLower)) {
                    PhpClass tr = lookupOrAutoloadClass(traitName);
                    if (tr == null) {
                        throw new PhpError("Trait \"" + traitName + "\" not found");
                    }
                    if (!tr.isTrait()) {
                        throw PhpError.fatal(template.name() + " cannot use " + tr.name() + " - it is not a trait");
                    }
                    resolvedTraits.put(tLower, tr);
                }
            }
        }
        if (!isBuiltin && parent != null) {
            for (PhpClassTemplate.MethodDecl m : template.methods().values()) {
                PhpClass.MethodEntry inherited = parent.lookupMethod(m.lowerName());
                if (inherited != null && inherited.isFinal()) {
                    throw PhpError.fatal("Cannot override final method " + inherited.declaringClass().name() + "::" + inherited.name() + "()");
                }
            }
        }
        PhpClass cls = new PhpClass(language, template, parent, ifaces, resolvedTraits);
        if (!template.isInterface() && !template.isTrait()) {
            if (cls.hasSplInterface(dev.phpruntime.runtime.SplInterfaces.ITERATOR)
                    && cls.hasSplInterface(dev.phpruntime.runtime.SplInterfaces.AGGREGATE)) {
                throw PhpError.fatal("Class " + template.name() + " cannot implement both Iterator and IteratorAggregate at the same time");
            }
            if (cls.hasSplInterface(dev.phpruntime.runtime.SplInterfaces.TRAVERSABLE)
                    && !cls.hasSplInterface(dev.phpruntime.runtime.SplInterfaces.ITERATOR)
                    && !cls.hasSplInterface(dev.phpruntime.runtime.SplInterfaces.AGGREGATE)) {
                throw PhpError.fatal("Class " + template.name()
                        + " must implement interface Traversable as part of either Iterator or IteratorAggregate");
            }
            if (cls.toStringMethod() != null
                    && !cls.hasSplInterface(dev.phpruntime.runtime.SplInterfaces.STRINGABLE)) {
                ifaces.add(lookupClass("Stringable"));
                cls = new PhpClass(language, template, parent, ifaces, resolvedTraits);
            }
            if (!isBuiltin && !cls.isAbstract()) {
                validateSplMethods(cls, cls);
            }
        }
        classes.put(lower, cls);
        int id = language.classId(lower);
        if (id >= classesById.length) {
            classesById = Arrays.copyOf(classesById, Math.max(id + 1, classesById.length * 2));
        }
        classesById[id] = cls;
        return cls;
    }

    private void validateSplMethods(PhpClass cls, PhpClass inherited) {
        if (inherited.parent() != null) { validateSplMethods(cls, inherited.parent()); }
        for (PhpClass iface : inherited.interfaces()) {
            for (var entry : iface.template().methodSignatures().entrySet()) {
                PhpClass.MethodEntry implementation = cls.lookupMethod(entry.getKey());
                if (implementation == null || implementation.callTarget() == null) {
                    throw PhpError.fatal("Class " + cls.name() + " contains 1 abstract method and must therefore be declared abstract or implement the remaining methods ("
                            + iface.name() + "::" + iface.template().methods().get(entry.getKey()).name() + ")");
                }
                if (implementation.isStatic()) {
                    throw PhpError.fatal("Cannot make non static method " + iface.name() + "::" + implementation.name()
                            + "() static in class " + cls.name());
                }
                if (implementation.visibility() != dev.phpruntime.runtime.Visibility.PUBLIC) {
                    throw PhpError.fatal("Access level to " + cls.name() + "::" + implementation.name() + "() must be public (as in interface " + iface.name() + ")");
                }
                if (implementation.callTarget().getRootNode() instanceof dev.phpruntime.nodes.PhpFunctionRootNode function) {
                    int required = entry.getValue().parameterNames().size();
                    if ((!function.isVariadic() && function.paramNames().length < required) || function.requiredArgs() > required) {
                        throw PhpError.fatal("Declaration of " + cls.name() + "::" + implementation.name() + "() must be compatible with "
                                + iface.name() + "::" + implementation.name() + "()");
                    }
                }
            }
            validateSplMethods(cls, iface);
        }
    }

    public long nextObjectId() {
        return nextObjectId++;
    }

    public long nextAnonymousId() {
        return nextAnonymousId++;
    }

    // ------------------------------------------------------------ constants

    @TruffleBoundary
    public boolean defineConstant(String name, Object value, int line) {
        String n = name.startsWith("\\") ? name.substring(1) : name;
        if (PhpConstants.lookupBuiltin(n) != null || constants.containsKey(n)) {
            warning("Constant " + n + " already defined", line);
            return false;
        }
        constants.put(n, value);
        return true;
    }

    @TruffleBoundary
    public boolean hasConstant(String name) {
        String n = name.startsWith("\\") ? name.substring(1) : name;
        int sep = n.indexOf("::");
        if (sep > 0) {
            PhpClass cls = lookupClass(n.substring(0, sep));
            return cls != null && cls.lookupConstant(n.substring(sep + 2)) != null;
        }
        return PhpConstants.lookupBuiltin(n) != null || constants.containsKey(n);
    }

    @TruffleBoundary
    public Object readConstant(String name) {
        String n = name.startsWith("\\") ? name.substring(1) : name;
        int sep = n.indexOf("::");
        if (sep > 0) {
            PhpClass cls = lookupClass(n.substring(0, sep));
            return cls == null ? null : cls.lookupConstant(n.substring(sep + 2));
        }
        Object builtin = PhpConstants.lookupBuiltin(n);
        if (builtin != null) {
            return builtin;
        }
        return constants.get(n);
    }

    // ------------------------------------------------------------ includes

    @TruffleBoundary
    public boolean isIncluded(String canonicalPath) {
        return includedFiles.contains(canonicalPath);
    }

    @TruffleBoundary
    public void markIncluded(String canonicalPath) {
        includedFiles.add(canonicalPath);
    }

    // ------------------------------------------------------------ output

    /** One ob_start() level: its buffered text and, for ob_list_handlers, its handler. */
    public static final class OutputBuffer {
        public final StringBuilder text = new StringBuilder();
        public Object handler;
        public String handlerName = "default output handler";
    }

    /** ob_start() levels, innermost last. Empty means echo goes straight to the response. */
    private final java.util.ArrayList<OutputBuffer> outputBuffers = new java.util.ArrayList<>();
    private boolean outputStarted;

    public java.util.List<OutputBuffer> outputBuffers() {
        return outputBuffers;
    }

    /** Appends to the innermost output buffer, or to the response when none is open. */
    @TruffleBoundary
    public void echo(String s) {
        if (PhpStrings.CHECK) {
            PhpStrings.check(s, byteStringOrigin == null ? "echo/output" : "echo/output in " + byteStringOrigin + "()");
        }
        if (!outputBuffers.isEmpty()) {
            outputBuffers.get(outputBuffers.size() - 1).text.append(s);
            return;
        }
        if (!s.isEmpty()) {
            outputStarted = true;
        }
        output.append(s);
        if (output.length() >= FLUSH_THRESHOLD) {
            flushOutput();
        }
    }

    /** headers_sent(): whether any output has gone past the output buffers. */
    public boolean outputStarted() {
        return outputStarted;
    }

    /** End of request: flushes any output buffers still open, innermost first, then the response. */
    @TruffleBoundary
    public void flush() {
        while (!outputBuffers.isEmpty()) {
            OutputBuffer top = outputBuffers.remove(outputBuffers.size() - 1);
            echo(applyObHandler(top, top.text.toString(), 9L));
        }
        flushOutput();
    }

    @TruffleBoundary
    public String applyObHandler(OutputBuffer buf, String text, long phase) {
        if (buf.handler == null || buf.handler == PhpNull.INSTANCE) {
            return text;
        }
        PhpCallable cb = PhpCallable.resolveOrNull(this, buf.handler);
        if (cb == null) {
            return text;
        }
        Object res = cb.call(this, null, null, text, phase);
        return Boolean.FALSE.equals(res) ? text : PhpOps.toStr(res);
    }

    /** Writes what has reached the response so far (PHP's flush()); open output buffers stay open. */
    @TruffleBoundary
    public void flushOutput() {
        if (output.isEmpty()) {
            return;
        }
        try {
            OutputStream out = env.out();
            out.write(PhpStrings.bytes(output.toString()));
            out.flush();
        } catch (IOException e) {
            throw new dev.phpruntime.runtime.PhpError("Failed writing output: " + PhpStrings.fromJava(e.getMessage()));
        }
        output.setLength(0);
    }

    @TruffleBoundary
    public void registerShutdownFunction(Object callable, Object[] args) {
        shutdownFunctions.add(new ShutdownEntry(callable, args));
    }

    @TruffleBoundary
    public void runShutdownFunctions() {
        for (int i = 0; i < shutdownFunctions.size(); i++) {
            ShutdownEntry entry = shutdownFunctions.get(i);
            PhpCallable cb = PhpCallable.resolveOrNull(this, entry.callable());
            if (cb != null) {
                try {
                    cb.call(this, null, null, entry.args());
                } catch (ControlFlow.Exit e) {
                    break;
                }
            }
        }
    }

    @TruffleBoundary
    public Object setErrorHandler(Object callable, long errorLevels) {
        ErrorHandlerEntry prev = errorHandlers.peek();
        errorHandlers.push(new ErrorHandlerEntry(callable, errorLevels));
        return prev == null ? PhpNull.INSTANCE : PhpOps.copyOnAssign(prev.callable());
    }

    @TruffleBoundary
    public boolean restoreErrorHandler() {
        if (!errorHandlers.isEmpty()) {
            errorHandlers.pop();
        }
        return true;
    }

    @TruffleBoundary
    public Object setExceptionHandler(Object callable) {
        Object prev = exceptionHandlers.peek();
        exceptionHandlers.push(callable);
        return prev == null ? PhpNull.INSTANCE : PhpOps.copyOnAssign(prev);
    }

    @TruffleBoundary
    public boolean restoreExceptionHandler() {
        if (!exceptionHandlers.isEmpty()) {
            exceptionHandlers.pop();
        }
        return true;
    }

    @TruffleBoundary
    public boolean handleUncaughtException(PhpObject exObj) {
        Object handler = exceptionHandlers.peek();
        if (handler == null || handler == PhpNull.INSTANCE) {
            return false;
        }
        PhpCallable cb = PhpCallable.resolveOrNull(this, handler);
        if (cb == null) {
            return false;
        }
        try {
            cb.call(this, null, null, exObj);
        } catch (ControlFlow.Exit ignored) {
        }
        return true;
    }

    @TruffleBoundary
    public void warning(String message, int line) {
        emitDiagnostic(2L, "Warning", message, line);
    }

    @TruffleBoundary
    public void notice(String message, int line) {
        emitDiagnostic(8L, "Notice", message, line);
    }

    /**
     * An error raised by the script itself (trigger_error/user_error), routed through
     * set_error_handler. Returns whether a handler accepted it (and so execution continues).
     */
    @TruffleBoundary
    public boolean userError(long errno, String message, int line) {
        String label = switch ((int) errno) {
            case 256 -> "Fatal error";
            case 512 -> "Warning";
            case 8192 -> "Deprecated";
            case 16384 -> "Deprecated";
            default -> "Notice";
        };
        return emitDiagnostic(errno, label, message, line);
    }

    /** The source file of the innermost PHP frame (an included file, not just the entry script). */
    @TruffleBoundary
    private String currentGuestFile() {
        String path = com.oracle.truffle.api.Truffle.getRuntime().iterateFrames(frame -> {
            if (frame.getCallTarget() instanceof com.oracle.truffle.api.RootCallTarget target
                    && target.getRootNode() instanceof dev.phpruntime.nodes.PhpRootNode root
                    && root.source() != null && root.source().getPath() != null) {
                return root.source().getPath();
            }
            return null;
        });
        return path != null ? path : request().scriptFilename;
    }

    @TruffleBoundary
    private boolean emitDiagnostic(long errno, String label, String message, int line) {
        String file = PhpStrings.fromJava(currentGuestFile());
        ErrorHandlerEntry handler = errorHandlers.peek();
        if (handler != null && handler.callable() != PhpNull.INSTANCE && !inErrorHandler
                && (handler.errorLevels() & errno) != 0) {
            PhpCallable cb = PhpCallable.resolveOrNull(this, handler.callable());
            if (cb != null) {
                inErrorHandler = true;
                try {
                    Object handled = cb.call(this, null, null, errno, message, file, (long) line);
                    if (!Boolean.FALSE.equals(handled)) {
                        return true;
                    }
                } finally {
                    inErrorHandler = false;
                }
            }
        }
        err.println("PHP " + label + ":  " + message + (file.isEmpty() ? "" : " in " + file) + (line > 0 ? " on line " + line : ""));
        return false;
    }
}
