package dev.phpruntime.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.object.DynamicObject;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.nodes.call.CallArgHelper;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpAttribute;
import dev.phpruntime.runtime.PhpCallable;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpClassTemplate;
import dev.phpruntime.runtime.PhpClosure;
import dev.phpruntime.runtime.EnumCaseState;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpExceptions;
import dev.phpruntime.runtime.PhpFunction;
import dev.phpruntime.runtime.PhpNativeState;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpParameter;
import dev.phpruntime.runtime.PhpReference;
import dev.phpruntime.runtime.PhpSignature;
import dev.phpruntime.runtime.PhpStrings;
import dev.phpruntime.runtime.PhpType;
import dev.phpruntime.runtime.Visibility;
import dev.phpruntime.snapshot.RestoredNativeState;
import dev.phpruntime.snapshot.SnapshotNativeState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The Reflection API: {@code Reflector}, the type classes, {@code ReflectionClass},
 * {@code ReflectionMethod}, {@code ReflectionFunction}, {@code ReflectionParameter},
 * {@code ReflectionProperty}, {@code ReflectionClassConstant} and {@code ReflectionAttribute}.
 *
 * <p>Each reflection object carries its target as a {@link PhpNativeState}; nothing here runs
 * on an ordinary call, property access or {@code new}. The metadata read was recorded by the
 * parser (see task 33).
 */
public final class ReflectionClasses {

    private ReflectionClasses() {
    }

    // ---------------------------------------------------------------- native state

    /** The PHP type behind a ReflectionType object. */
    private record TypeState(PhpType type) implements PhpNativeState {
        @Override
        public PhpNativeState copy() {
            return new TypeState(type);
        }
    }

    /** The class (and, for ReflectionObject, the instance) behind a ReflectionClass. */
    private record ClassState(PhpClass cls, PhpObject object)
            implements PhpNativeState, SnapshotNativeState {
        @Override
        public PhpNativeState copy() {
            return new ClassState(cls, object);
        }

        @Override
        public Object snapshotNativeState(Function<Object, Object> map) {
            return new ClassStateSnapshot(cls, map.apply(object));
        }

        private record ClassStateSnapshot(PhpClass cls, Object object) implements RestoredNativeState {
            @Override
            public PhpNativeState restoreNativeState(PhpContext ctx, Function<Object, Object> unmap) {
                return new ClassState(cls, object == null ? null : (PhpObject) unmap.apply(object));
            }
        }
    }

    /** A function, closure or first-class callable behind a ReflectionFunction. */
    private static final class FunctionState implements PhpNativeState, SnapshotNativeState {
        final String name;
        final PhpCallable callable;
        final PhpObject closureObject;
        final PhpFunctionRootNode root;
        final Builtins.Def builtin;
        final PhpSignature signature;
        final boolean closure;
        final boolean internal;

        FunctionState(String name, PhpCallable callable, PhpObject closureObject, PhpFunctionRootNode root,
                      Builtins.Def builtin, PhpSignature signature, boolean closure, boolean internal) {
            this.name = name;
            this.callable = callable;
            this.closureObject = closureObject;
            this.root = root;
            this.builtin = builtin;
            this.signature = signature == null ? PhpSignature.EMPTY : signature;
            this.closure = closure;
            this.internal = internal;
        }

        @Override
        public PhpNativeState copy() {
            return this;
        }

        @Override
        public Object snapshotNativeState(Function<Object, Object> map) {
            return new FunctionStateSnapshot(name, map.apply(closureObject), root, builtin, signature,
                    closure, internal);
        }

        private record FunctionStateSnapshot(String name, Object closureObject, PhpFunctionRootNode root,
                                             Builtins.Def builtin, PhpSignature signature, boolean closure,
                                             boolean internal) implements RestoredNativeState {
            @Override
            public PhpNativeState restoreNativeState(PhpContext ctx, Function<Object, Object> unmap) {
                PhpObject object = closureObject == null ? null : (PhpObject) unmap.apply(closureObject);
                PhpCallable restored;
                if (object != null) {
                    restored = PhpCallable.resolveOrNull(ctx, object);
                } else if (builtin != null) {
                    restored = PhpCallable.resolveOrNull(ctx, builtin.name());
                } else {
                    restored = PhpCallable.resolveOrNull(ctx, name);
                }
                return new FunctionState(name, restored, object, root, builtin, signature, closure, internal);
            }
        }
    }

    /** A method behind a ReflectionMethod. */
    private record MethodState(PhpClass reflected, PhpClass.MethodEntry entry, PhpClass declaring,
                               PhpSignature signature, PhpFunctionRootNode root, boolean internal)
            implements PhpNativeState {
        @Override
        public PhpNativeState copy() {
            return new MethodState(reflected, entry, declaring, signature, root, internal);
        }
    }

    /** One parameter, with the reflection object of its declaring function. */
    private record ParameterState(PhpParameter parameter, int position, PhpClass declaringClass,
                                  PhpFunctionRootNode root, int requiredCount, boolean internal,
                                  PhpObject declaringFunction)
            implements PhpNativeState, SnapshotNativeState {
        @Override
        public PhpNativeState copy() {
            return this;
        }

        @Override
        public Object snapshotNativeState(Function<Object, Object> map) {
            return new ParameterStateSnapshot(parameter, position, declaringClass, root, requiredCount,
                    internal, map.apply(declaringFunction));
        }

        private record ParameterStateSnapshot(PhpParameter parameter, int position, PhpClass declaringClass,
                                              PhpFunctionRootNode root, int requiredCount, boolean internal,
                                              Object declaringFunction) implements RestoredNativeState {
            @Override
            public PhpNativeState restoreNativeState(PhpContext ctx, Function<Object, Object> unmap) {
                return new ParameterState(parameter, position, declaringClass, root, requiredCount,
                        internal, (PhpObject) unmap.apply(declaringFunction));
            }
        }
    }

    /** A declared (or dynamic) property. */
    private record PropertyState(PhpClass reflected, PhpClass declaring, PhpClassTemplate.PropertyDecl decl,
                                 boolean dynamic, String dynamicName) implements PhpNativeState {
        @Override
        public PhpNativeState copy() {
            return this;
        }

        String name() {
            return dynamic ? dynamicName : decl.name();
        }
    }

    /** An enum case behind a ReflectionEnumUnitCase or ReflectionEnumBackedCase. */
    private record EnumCaseRefState(PhpClass enumClass, PhpClassTemplate.EnumCaseDecl decl)
            implements PhpNativeState {
        @Override
        public PhpNativeState copy() {
            return this;
        }
    }

    /** A declared class constant, or an enum case seen as one. */
    private record ConstantState(PhpClass reflected, PhpClass declaring,
                                 PhpClassTemplate.ConstantDecl decl, boolean enumCase) implements PhpNativeState {
        @Override
        public PhpNativeState copy() {
            return this;
        }
    }

    /** One attribute occurrence, with the declaration it appears on. */
    private record AttributeState(PhpAttribute attribute, int target, boolean repeated)
            implements PhpNativeState, SnapshotNativeState {
        @Override
        public PhpNativeState copy() {
            return this;
        }

        @Override
        public Object snapshotNativeState(Function<Object, Object> map) {
            return new AttributeStateSnapshot(copyAttribute(attribute, map), target, repeated);
        }

        private record AttributeStateSnapshot(PhpAttribute attribute, int target, boolean repeated)
                implements RestoredNativeState {
            @Override
            public PhpNativeState restoreNativeState(PhpContext ctx, Function<Object, Object> unmap) {
                return new AttributeState(copyAttribute(attribute, unmap), target, repeated);
            }
        }
    }

    /** Copies an attribute's argument values through {@code map}, keeping targets shared. */
    private static PhpAttribute copyAttribute(PhpAttribute attribute, Function<Object, Object> map) {
        List<PhpAttribute.Argument> arguments = new ArrayList<>(attribute.arguments().size());
        for (PhpAttribute.Argument argument : attribute.arguments()) {
            arguments.add(new PhpAttribute.Argument(argument.name(), map.apply(argument.value()),
                    argument.target()));
        }
        return new PhpAttribute(attribute.name(), arguments);
    }

    // ---------------------------------------------------------------- registration

    public static void buildTemplates(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        buildReflector(language, out);
        buildTypeClasses(language, out);
        buildReflectionClass(language, out);
        buildReflectionFunctionAbstract(language, out);
        buildReflectionMethod(language, out);
        buildReflectionFunction(language, out);
        buildReflectionParameter(language, out);
        buildReflectionProperty(language, out);
        buildReflectionClassConstant(language, out);
        buildReflectionReference(language, out);
        buildReflectionEnum(language, out);
        buildReflectionEnumUnitCase(language, out);
        buildReflectionEnumBackedCase(language, out);
        buildReflectionAttribute(language, out);
        buildPropertyHookType(language, out);
        buildAttributeMarker(language, out);
        buildReflectionException(language, out);
    }

    private static void register(Map<String, PhpClassTemplate> out, PhpClassTemplate template) {
        out.put(template.lowerName(), template);
    }

    private static void buildReflector(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        addAbstractMethod(language, methods, "__toString");
        register(out, new PhpClassTemplate(language.nextTemplateId(), "Reflector", true, false, false, false,
                false, null, List.of("Stringable"), List.of(), List.of(), List.of(), methods));
    }

    private static void buildTypeClasses(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        Map<String, PhpClassTemplate.MethodDecl> base = new LinkedHashMap<>();
        addMethod(language, base, "__toString", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PhpType type = typeState(self).type();
            return type == null ? "" : type.toString();
        });
        addMethod(language, base, "allowsNull", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            return typeAllowsNull(typeState(self).type());
        });
        register(out, new PhpClassTemplate(language.nextTemplateId(), "ReflectionType", false, false, true, false,
                false, null, List.of("Stringable"), List.of(), List.of(), List.of(), base));

        Map<String, PhpClassTemplate.MethodDecl> named = new LinkedHashMap<>();
        addMethod(language, named, "getName", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PhpType type = typeState(self).type();
            if (type instanceof PhpType.Nullable nullable) {
                type = nullable.inner();
            }
            return type instanceof PhpType.Named n ? n.name() : type.toString();
        });
        addMethod(language, named, "isBuiltin", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            return typeIsBuiltin(typeState(self).type());
        });
        register(out, new PhpClassTemplate(language.nextTemplateId(), "ReflectionNamedType", false, false, false,
                false, false, "ReflectionType", List.of(), List.of(), List.of(), List.of(), named));

        Map<String, PhpClassTemplate.MethodDecl> union = new LinkedHashMap<>();
        addMethod(language, union, "getTypes", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> typeMembers(ctx, typeState(self).type()));
        register(out, new PhpClassTemplate(language.nextTemplateId(), "ReflectionUnionType", false, false, false,
                false, false, "ReflectionType", List.of(), List.of(), List.of(), List.of(), union));

        Map<String, PhpClassTemplate.MethodDecl> intersection = new LinkedHashMap<>();
        addMethod(language, intersection, "getTypes", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> typeMembers(ctx, typeState(self).type()));
        register(out, new PhpClassTemplate(language.nextTemplateId(), "ReflectionIntersectionType", false, false,
                false, false, false, "ReflectionType", List.of(), List.of(), List.of(), List.of(), intersection));
    }

    private static TypeState typeState(PhpObject self) {
        if (self != null && self.nativeState() instanceof TypeState state) {
            return state;
        }
        throw new PhpError("ReflectionType object is not initialized");
    }

    private static boolean typeAllowsNull(PhpType type) {
        if (type == null) {
            return false;
        }
        if (type instanceof PhpType.Nullable) {
            return true;
        }
        if (type instanceof PhpType.Named named) {
            String n = named.name();
            return PhpStrings.equalsIgnoreCase(n, "null") || PhpStrings.equalsIgnoreCase(n, "mixed");
        }
        if (type instanceof PhpType.Union union) {
            for (PhpType member : union.members()) {
                if (typeAllowsNull(member)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean typeIsBuiltin(PhpType type) {
        if (type instanceof PhpType.Nullable nullable) {
            type = nullable.inner();
        }
        if (!(type instanceof PhpType.Named named)) {
            return false;
        }
        return switch (PhpStrings.lower(named.name())) {
            case "int", "float", "string", "bool", "array", "callable", "iterable", "object",
                    "mixed", "void", "never", "null", "false", "true" -> true;
            default -> false;
        };
    }

    private static PhpArray typeMembers(PhpContext ctx, PhpType type) {
        PhpArray out = new PhpArray();
        List<PhpType> members;
        if (type instanceof PhpType.Union union) {
            members = orderedUnionMembers(union.members());
        } else if (type instanceof PhpType.Intersection intersection) {
            members = intersection.members();
        } else {
            members = List.of();
        }
        for (PhpType member : members) {
            out.append(typeObject(ctx, member instanceof PhpType.Nullable nullable ? nullable.inner() : member));
        }
        return out;
    }

    /**
     * The order ReflectionUnionType::getTypes() uses, which is the order the type string puts
     * members in: classes and intersections as written, then static, the built-in types in
     * PHP's canonical order, and null last.
     */
    private static List<PhpType> orderedUnionMembers(List<PhpType> written) {
        List<PhpType> classes = new ArrayList<>();
        List<PhpType> builtins = new ArrayList<>();
        PhpType staticType = null;
        PhpType nullType = null;
        for (PhpType member : written) {
            if (member instanceof PhpType.Named named) {
                String lower = PhpStrings.lower(named.name());
                if (lower.equals("null")) {
                    nullType = member;
                } else if (lower.equals("static")) {
                    staticType = member;
                } else if (builtinRank(lower) >= 0) {
                    builtins.add(member);
                } else {
                    classes.add(member);
                }
            } else if (member instanceof PhpType.Intersection) {
                classes.add(member);
            } else {
                classes.add(member);
            }
        }
        builtins.sort((a, b) -> Integer.compare(
                builtinRank(PhpStrings.lower(((PhpType.Named) a).name())),
                builtinRank(PhpStrings.lower(((PhpType.Named) b).name()))));
        List<PhpType> out = new ArrayList<>(written.size());
        out.addAll(classes);
        if (staticType != null) {
            out.add(staticType);
        }
        out.addAll(builtins);
        if (nullType != null) {
            out.add(nullType);
        }
        return out;
    }

    private static int builtinRank(String lower) {
        return switch (lower) {
            case "callable" -> 0;
            case "object" -> 1;
            case "array" -> 2;
            case "string" -> 3;
            case "int" -> 4;
            case "float" -> 5;
            case "bool" -> 6;
            case "false" -> 7;
            case "true" -> 8;
            default -> -1;
        };
    }

    private static PhpObject typeObject(PhpContext ctx, PhpType type) {
        String className;
        if (type instanceof PhpType.Union) {
            className = "ReflectionUnionType";
        } else if (type instanceof PhpType.Intersection) {
            className = "ReflectionIntersectionType";
        } else {
            className = "ReflectionNamedType";
        }
        return newReflectionObject(ctx, className, new TypeState(type));
    }

    private static void buildReflectionException(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        register(out, new PhpClassTemplate(language.nextTemplateId(), "ReflectionException", false, false, false,
                false, false, "Exception", List.of(), List.of(), List.of(), List.of(), Map.of()));
    }

    private static void buildAttributeMarker(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        List<PhpClassTemplate.ConstantDecl> constants = new ArrayList<>();
        constant(constants, "TARGET_CLASS", 1L, Visibility.PUBLIC, false);
        constant(constants, "TARGET_FUNCTION", 2L, Visibility.PUBLIC, false);
        constant(constants, "TARGET_METHOD", 4L, Visibility.PUBLIC, false);
        constant(constants, "TARGET_PROPERTY", 8L, Visibility.PUBLIC, false);
        constant(constants, "TARGET_CLASS_CONSTANT", 16L, Visibility.PUBLIC, false);
        constant(constants, "TARGET_PARAMETER", 32L, Visibility.PUBLIC, false);
        constant(constants, "TARGET_CONSTANT", 64L, Visibility.PUBLIC, false);
        constant(constants, "TARGET_ALL", 127L, Visibility.PUBLIC, false);
        constant(constants, "IS_REPEATABLE", 128L, Visibility.PUBLIC, false);
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        addMethod(language, methods, "__construct", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> PhpNull.INSTANCE);
        register(out, new PhpClassTemplate(language.nextTemplateId(), "Attribute", false, false, false, true,
                false, null, List.of(), List.of(), List.of(), constants, methods));
    }

    // ---------------------------------------------------------------- ReflectionClass

    private static void buildReflectionClass(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        addMethod(language, methods, "__construct", Visibility.PUBLIC, false, false,
                List.of("objectOrClass"), (ctx, self, args) -> {
            requireArgCount("ReflectionClass::__construct", args, 1, 1);
            PhpClass cls;
            if (args[0] instanceof PhpObject object) {
                cls = object.phpClass();
            } else {
                String name = PhpOps.toStr(args[0]);
                cls = ctx.lookupOrAutoloadClass(name);
                if (cls == null) {
                    throw reflectionError("Class \"" + name + "\" does not exist");
                }
            }
            attach(self, new ClassState(cls, args[0] instanceof PhpObject object ? object : null));
            return PhpNull.INSTANCE;
        });
        addMethod(language, methods, "getName", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> classState(self).cls().name());
        addMethod(language, methods, "getShortName", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> shortName(classState(self).cls().name()));
        addMethod(language, methods, "getNamespaceName", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> namespaceName(classState(self).cls().name()));
        addMethod(language, methods, "inNamespace", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> !namespaceName(classState(self).cls().name()).isEmpty());
        addMethod(language, methods, "isInterface", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> classState(self).cls().isInterface());
        addMethod(language, methods, "isTrait", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> classState(self).cls().isTrait());
        addMethod(language, methods, "isEnum", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> classState(self).cls().template().isEnum());
        addMethod(language, methods, "isAbstract", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> classState(self).cls().isAbstract());
        addMethod(language, methods, "isFinal", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> classState(self).cls().isFinal() || classState(self).cls().template().isEnum());
        addMethod(language, methods, "isInstantiable", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> isInstantiable(classState(self).cls()));
        addMethod(language, methods, "isInternal", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> isInternalClass(ctx, classState(self).cls()));
        addMethod(language, methods, "isUserDefined", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> !isInternalClass(ctx, classState(self).cls()));
        addMethod(language, methods, "isAnonymous", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> classState(self).cls().template().isAnonymous());
        addMethod(language, methods, "isCloneable", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> isCloneableClass(classState(self).cls()));
        addMethod(language, methods, "isUninitializedLazyObject", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            requireArgCount("ReflectionClass::isUninitializedLazyObject", args, 1, 1);
            ClassState state = classState(self);
            if (!(args[0] instanceof PhpObject object) || !object.phpClass().isInstanceOf(state.cls())) {
                throw new PhpError(PhpError.TYPE_ERROR,
                        "ReflectionClass::isUninitializedLazyObject(): Argument #1 ($object) must be of type "
                                + state.cls().name() + ", " + valueTypeName(args[0]) + " given", null);
            }
            return false;
        });
        addMethod(language, methods, "getParentClass", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PhpClass parent = classState(self).cls().parent();
            return parent == null ? Boolean.FALSE : reflectionClassObject(ctx, parent, null);
        });
        addMethod(language, methods, "isSubclassOf", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            requireArgCount("ReflectionClass::isSubclassOf", args, 1, 1);
            PhpClass cls = classState(self).cls();
            String name = PhpOps.toStr(args[0]);
            PhpClass target = ctx.lookupOrAutoloadClass(name);
            if (target == null) {
                throw reflectionError("Class \"" + name + "\" does not exist");
            }
            return target != cls && cls.isInstanceOf(target);
        });
        addMethod(language, methods, "implementsInterface", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            requireArgCount("ReflectionClass::implementsInterface", args, 1, 1);
            PhpClass cls = classState(self).cls();
            String name = PhpOps.toStr(args[0]);
            PhpClass target = ctx.lookupOrAutoloadClass(name);
            if (target == null) {
                throw reflectionError("Interface \"" + name + "\" does not exist");
            }
            if (!target.isInterface()) {
                throw reflectionError(name + " is not an interface");
            }
            return cls.isInstanceOf(target);
        });
        addMethod(language, methods, "getInterfaceNames", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            List<PhpClass> interfaces = collectInterfaces(classState(self).cls());
            PhpArray result = new PhpArray();
            for (PhpClass iface : interfaces) {
                result.append(iface.name());
            }
            return result;
        });
        addMethod(language, methods, "getInterfaces", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PhpArray result = new PhpArray();
            for (PhpClass iface : collectInterfaces(classState(self).cls())) {
                result.put(iface.name(), reflectionClassObject(ctx, iface, null));
            }
            return result;
        });
        addMethod(language, methods, "getTraitNames", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PhpArray result = new PhpArray();
            for (PhpClass trait : classState(self).cls().usedTraits()) {
                result.append(trait.name());
            }
            return result;
        });
        addMethod(language, methods, "getTraits", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PhpArray result = new PhpArray();
            for (PhpClass trait : classState(self).cls().usedTraits()) {
                result.put(trait.name(), reflectionClassObject(ctx, trait, null));
            }
            return result;
        });
        addMethod(language, methods, "getConstructor", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PhpClass cls = classState(self).cls();
            PhpClass.MethodEntry entry = cls.constructMethod();
            return entry == null ? PhpNull.INSTANCE : reflectionMethodObject(ctx, cls, entry);
        });
        addMethod(language, methods, "hasMethod", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            requireArgCount("ReflectionClass::hasMethod", args, 1, 1);
            return findMethod(classState(self).cls(), PhpOps.toStr(args[0])) != null;
        });
        addMethod(language, methods, "getMethod", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            requireArgCount("ReflectionClass::getMethod", args, 1, 1);
            PhpClass cls = classState(self).cls();
            String name = PhpOps.toStr(args[0]);
            PhpClass.MethodEntry entry = findMethod(cls, name);
            if (entry == null) {
                throw reflectionError("Method " + cls.name() + "::" + name + "() does not exist");
            }
            return reflectionMethodObject(ctx, cls, entry);
        });
        addMethod(language, methods, "getMethods", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PhpClass cls = classState(self).cls();
            Integer filter = args.length > 0 && args[0] != PhpNull.INSTANCE ? (int) toLong(args[0]) : null;
            PhpArray result = new PhpArray();
            for (MethodState method : collectMethods(cls)) {
                if (filter != null && (methodModifiers(method) & filter) == 0) {
                    continue;
                }
                result.append(methodObject(ctx, method));
            }
            return result;
        });
        addMethod(language, methods, "hasProperty", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            requireArgCount("ReflectionClass::hasProperty", args, 1, 1);
            ClassState state = classState(self);
            String name = PhpOps.toStr(args[0]);
            return findProperty(state, name) != null || hasDynamicProperty(state, name);
        });
        addMethod(language, methods, "getProperty", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            requireArgCount("ReflectionClass::getProperty", args, 1, 1);
            ClassState state = classState(self);
            String name = PhpOps.toStr(args[0]);
            PropertyInfo info = findProperty(state, name);
            if (info == null) {
                if (hasDynamicProperty(state, name)) {
                    return newReflectionObject(ctx, "ReflectionProperty",
                            new PropertyState(state.cls(), null, null, true, name));
                }
                throw reflectionError("Property " + state.cls().name() + "::$" + name + " does not exist");
            }
            return propertyObject(ctx, state.cls(), info);
        });
        addMethod(language, methods, "getProperties", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            ClassState state = classState(self);
            Integer filter = args.length > 0 && args[0] != PhpNull.INSTANCE ? (int) toLong(args[0]) : null;
            PhpArray result = new PhpArray();
            for (PropertyInfo info : collectProperties(state.cls())) {
                PhpClass.InstanceProp effective = info.declaring() == null ? null
                        : info.declaring().findVisibleProp(info.decl().name());
                if (filter != null && (propertyModifiers(info.decl(), effective) & filter) == 0) {
                    continue;
                }
                result.append(propertyObject(ctx, state.cls(), info));
            }
            return result;
        });
        addMethod(language, methods, "hasConstant", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            requireArgCount("ReflectionClass::hasConstant", args, 1, 1);
            return findConstant(classState(self).cls(), PhpOps.toStr(args[0])) != null;
        });
        addMethod(language, methods, "getConstant", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            requireArgCount("ReflectionClass::getConstant", args, 1, 1);
            String name = PhpOps.toStr(args[0]);
            ConstantInfo info = findConstant(classState(self).cls(), name);
            return info == null ? Boolean.FALSE : info.declaring().lookupConstant(ctx, info.decl().name());
        });
        addMethod(language, methods, "getConstants", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            Integer filter = args.length > 0 && args[0] != PhpNull.INSTANCE ? (int) toLong(args[0]) : null;
            PhpArray result = new PhpArray();
            for (ConstantInfo info : collectConstants(classState(self).cls())) {
                if (filter != null && (constantModifiers(info.decl()) & filter) == 0) {
                    continue;
                }
                result.put(info.decl().name(), info.declaring().lookupConstant(ctx, info.decl().name()));
            }
            return result;
        });
        addMethod(language, methods, "getReflectionConstant", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            requireArgCount("ReflectionClass::getReflectionConstant", args, 1, 1);
            String name = PhpOps.toStr(args[0]);
            ConstantInfo info = findConstant(classState(self).cls(), name);
            return info == null ? Boolean.FALSE : constantObject(ctx, classState(self).cls(), info);
        });
        addMethod(language, methods, "getReflectionConstants", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PhpArray result = new PhpArray();
            for (ConstantInfo info : collectConstants(classState(self).cls())) {
                result.append(constantObject(ctx, classState(self).cls(), info));
            }
            return result;
        });
        addMethod(language, methods, "getAttributes", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            ClassState state = classState(self);
            return attributes(ctx, state.cls().template().attributes(), args, TARGET_CLASS);
        });
        addMethod(language, methods, "newInstance", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            dev.phpruntime.runtime.ForwardedNamedArgs forwarded = ctx.takeForwardedNamed();
            PhpArray values = positionalArray(args);
            if (forwarded != null) {
                for (var entry : forwarded.values().entrySet()) {
                    values.put(entry.getKey(), entry.getValue());
                }
            }
            return instantiate(ctx, classState(self).cls(), values, false, null);
        });
        addMethod(language, methods, "newInstanceArgs", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PhpArray values = args.length > 0 && args[0] instanceof PhpArray a ? a : new PhpArray();
            return instantiate(ctx, classState(self).cls(), values, false, null);
        });
        addMethod(language, methods, "newInstanceWithoutConstructor", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> instantiate(ctx, classState(self).cls(), new PhpArray(), true, null));
        addMethod(language, methods, "isInstance", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            requireArgCount("ReflectionClass::isInstance", args, 1, 1);
            if (!(args[0] instanceof PhpObject object)) {
                throw new PhpError(PhpError.TYPE_ERROR,
                        "ReflectionClass::isInstance(): Argument #1 ($object) must be of type object, "
                                + PhpOps.typeName(args[0]) + " given", null);
            }
            return object.phpClass().isInstanceOf(classState(self).cls());
        });
        addMethod(language, methods, "getFileName", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            String file = classState(self).cls().template().sourceFile();
            return file == null ? Boolean.FALSE : file;
        });
        addMethod(language, methods, "getStartLine", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            int line = classState(self).cls().template().startLine();
            return line < 0 ? Boolean.FALSE : (long) line;
        });
        addMethod(language, methods, "getEndLine", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            int line = classState(self).cls().template().endLine();
            return line < 0 ? Boolean.FALSE : (long) line;
        });
        addMethod(language, methods, "getDocComment", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> Boolean.FALSE);
        addMethod(language, methods, "getStaticPropertyValue", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            requireArgCount("ReflectionClass::getStaticPropertyValue", args, 1, 2);
            ClassState state = classState(self);
            String name = PhpOps.toStr(args[0]);
            PropertyInfo info = findProperty(state, name);
            if (info == null || !info.decl().isStatic()) {
                if (args.length > 1) {
                    return PhpOps.copyOnAssign(args[1]);
                }
                throw reflectionError("Property " + state.cls().name() + "::$" + name + " does not exist");
            }
            PhpClass.StaticSlot slot = info.declaring().lookupStaticProperty(ctx, name);
            if (slot == null) {
                return PhpNull.INSTANCE;
            }
            if (!staticPropertyInitialized(slot, info)) {
                throw new PhpError("Typed property " + info.declaring().name() + "::$" + name
                        + " must not be accessed before initialization");
            }
            return slot.get();
        });
        addMethod(language, methods, "setStaticPropertyValue", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            requireArgCount("ReflectionClass::setStaticPropertyValue", args, 2, 2);
            ClassState state = classState(self);
            String name = PhpOps.toStr(args[0]);
            PropertyInfo info = findProperty(state, name);
            if (info == null || !info.decl().isStatic()) {
                throw reflectionError("Class " + state.cls().name() + " does not have a property named " + name);
            }
            PhpClass.StaticSlot slot = info.declaring().lookupStaticProperty(ctx, name);
            if (slot == null) {
                throw reflectionError("Class " + state.cls().name() + " does not have a property named " + name);
            }
            slot.set(PhpOps.copyOnAssign(args[1]));
            return PhpNull.INSTANCE;
        });
        addMethod(language, methods, "getStaticProperties", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PhpArray result = new PhpArray();
            for (PropertyInfo info : collectProperties(classState(self).cls())) {
                if (!info.decl().isStatic()) {
                    continue;
                }
                PhpClass.StaticSlot slot = info.declaring().lookupStaticProperty(ctx, info.decl().name());
                if (slot != null && staticPropertyInitialized(slot, info)) {
                    result.put(info.decl().name(), slot.get());
                }
            }
            return result;
        });
        addMethod(language, methods, "getDefaultProperties", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PhpArray result = new PhpArray();
            List<PropertyInfo> properties = collectProperties(classState(self).cls());
            for (PropertyInfo info : properties) {
                if (info.decl().isStatic() && propertyHasDefault(info)) {
                    result.put(info.decl().name(), info.declaring().evaluatePropertyDefault(info.decl()));
                }
            }
            for (PropertyInfo info : properties) {
                if (!info.decl().isStatic() && propertyHasDefault(info)) {
                    result.put(info.decl().name(), info.declaring().evaluatePropertyDefault(info.decl()));
                }
            }
            return result;
        });
        addMethod(language, methods, "getModifiers", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PhpClass cls = classState(self).cls();
            long mods = 0;
            if (cls.isAbstract()) {
                mods |= 64;
            }
            if (cls.isFinal() || cls.template().isEnum()) {
                mods |= 32;
            }
            return mods;
        });
        addMethod(language, methods, "setAccessible", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> PhpNull.INSTANCE);
        addMethod(language, methods, "__toString", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            ClassState state = classState(self);
            return state.cls().name();
        });

        List<PhpClassTemplate.ConstantDecl> constants = new ArrayList<>();
        constant(constants, "IS_IMPLICIT_ABSTRACT", 16L, Visibility.PUBLIC, false);
        constant(constants, "IS_EXPLICIT_ABSTRACT", 64L, Visibility.PUBLIC, false);
        constant(constants, "IS_FINAL", 32L, Visibility.PUBLIC, false);
        constant(constants, "IS_READONLY", 65536L, Visibility.PUBLIC, false);
        constant(constants, "SKIP_INITIALIZATION_ON_SERIALIZE", 8L, Visibility.PUBLIC, false);
        constant(constants, "SKIP_DESTRUCTOR", 16L, Visibility.PUBLIC, false);
        register(out, new PhpClassTemplate(language.nextTemplateId(), "ReflectionClass", false, false, false, false,
                false, null, List.of("Stringable", "Reflector"), List.of(), nameProps(), constants, methods));

        Map<String, PhpClassTemplate.MethodDecl> objectMethods = new LinkedHashMap<>();
        addMethod(language, objectMethods, "__construct", Visibility.PUBLIC, false, false,
                List.of("object"), (ctx, self, args) -> {
            requireArgCount("ReflectionObject::__construct", args, 1, 1);
            if (!(args[0] instanceof PhpObject object)) {
                throw new PhpError(PhpError.TYPE_ERROR,
                        "ReflectionObject::__construct(): Argument #1 ($object) must be of type object, "
                                + PhpOps.typeName(args[0]) + " given", null);
            }
            attach(self, new ClassState(object.phpClass(), object));
            return PhpNull.INSTANCE;
        });
        register(out, new PhpClassTemplate(language.nextTemplateId(), "ReflectionObject", false, false, false, false,
                false, "ReflectionClass", List.of(), List.of(), List.of(), List.of(), objectMethods));
    }

    private static ClassState classState(PhpObject self) {
        if (self != null && self.nativeState() instanceof ClassState state) {
            return state;
        }
        throw new PhpError("ReflectionClass object is not initialized");
    }

    private static boolean isInstantiable(PhpClass cls) {
        if (cls == null || cls.isInterface() || cls.isTrait() || cls.isAbstract() || cls.template().isEnum()
                || "Closure".equalsIgnoreCase(cls.name())) {
            return false;
        }
        PhpClass.MethodEntry ctor = cls.constructMethod();
        return ctor == null || ctor.visibility() == Visibility.PUBLIC;
    }

    /**
     * Internal classes whose engine clone handler is absent. A user subclass inherits that
     * unless it declares a public {@code __clone}, which is how php reports it.
     */
    private static final Set<String> NON_CLONEABLE_INTERNAL = Set.of(
            "generator", "fiber", "weakreference", "directory", "pdo", "pdostatement", "collator",
            "random\\randomizer", "reflectionreference",
            "reflectionclass", "reflectionobject", "reflectionfunctionabstract", "reflectionfunction",
            "reflectionmethod", "reflectionparameter", "reflectionproperty", "reflectionclassconstant",
            "reflectionenum", "reflectionenumunitcase", "reflectionenumbackedcase", "reflectionattribute",
            "reflectiontype", "reflectionnamedtype", "reflectionuniontype", "reflectionintersectiontype",
            "exception", "errorexception", "error", "typeerror", "valueerror", "argumentcounterror",
            "arithmeticerror", "divisionbyzeroerror", "parseerror", "unhandledmatcherror", "compileerror");

    private static boolean isCloneableClass(PhpClass cls) {
        if (cls.isInterface() || cls.isTrait() || cls.isAbstract() || cls.template().isEnum()) {
            return false;
        }
        PhpClass.MethodEntry clone = cls.lookupMethod("__clone");
        if (clone != null) {
            return clone.visibility() == Visibility.PUBLIC;
        }
        for (PhpClass c = cls; c != null; c = c.parent()) {
            if (c.template().sourceFile() == null && NON_CLONEABLE_INTERNAL.contains(c.lowerName())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isInternalClass(PhpContext ctx, PhpClass cls) {
        return cls.template().sourceFile() == null;
    }

    // ---------------------------------------------------------------- method metadata

    private static List<MethodState> collectMethods(PhpClass cls) {
        List<MethodState> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        collectClassMethods(cls, true, out, seen);
        for (PhpClass iface : collectInterfaces(cls)) {
            collectInterfaceMethods(iface, out, seen);
        }
        return out;
    }

    private static void collectClassMethods(PhpClass cls, boolean own, List<MethodState> out, Set<String> seen) {
        if (cls == null) {
            return;
        }
        for (PhpClassTemplate.MethodDecl decl : cls.template().methods().values()) {
            if (!own && decl.visibility() == Visibility.PRIVATE) {
                continue;
            }
            if (!seen.add(decl.lowerName())) {
                continue;
            }
            PhpClass.MethodEntry entry = cls.lookupMethod(decl.name());
            if (entry != null) {
                out.add(methodState(cls, entry));
            }
        }
        Map<String, PhpClass> traits = new LinkedHashMap<>();
        for (PhpClass trait : cls.usedTraits()) {
            traits.put(trait.lowerName(), trait);
        }
        for (PhpClassTemplate.TraitUse use : cls.template().traitUses()) {
            for (PhpClassTemplate.TraitAlias alias : use.aliases()) {
                String aliasName = alias.aliasName() != null ? alias.aliasName() : alias.methodName();
                String aliasLower = PhpStrings.lower(aliasName);
                if (!seen.add(aliasLower)) {
                    continue;
                }
                PhpClass.MethodEntry entry = cls.lookupMethod(aliasName);
                if (entry != null) {
                    out.add(methodState(cls, entry));
                }
            }
            for (String traitName : use.traitNames()) {
                PhpClass trait = traits.get(PhpStrings.lower(traitName));
                if (trait != null) {
                    collectTraitMethods(cls, trait, own, out, seen);
                }
            }
        }
        collectClassMethods(cls.parent(), false, out, seen);
    }

    private static void collectTraitMethods(PhpClass using, PhpClass trait, boolean own,
                                            List<MethodState> out, Set<String> seen) {
        for (PhpClassTemplate.MethodDecl decl : trait.template().methods().values()) {
            if (!own && decl.visibility() == Visibility.PRIVATE) {
                continue;
            }
            if (!seen.add(decl.lowerName())) {
                continue;
            }
            PhpClass.MethodEntry entry = using.lookupMethod(decl.name());
            if (entry != null) {
                out.add(methodState(using, entry));
            }
        }
        for (PhpClass nested : trait.usedTraits()) {
            collectTraitMethods(using, nested, own, out, seen);
        }
    }

    private static void collectInterfaceMethods(PhpClass iface, List<MethodState> out, Set<String> seen) {
        for (PhpClassTemplate.MethodDecl decl : iface.template().methods().values()) {
            if (!seen.add(decl.lowerName())) {
                continue;
            }
            PhpClass.MethodEntry entry = iface.lookupMethod(decl.name());
            if (entry != null) {
                out.add(methodState(iface, entry));
            }
        }
        for (PhpClass parent : iface.interfaces()) {
            collectInterfaceMethods(parent, out, seen);
        }
    }

    private static PhpClass.MethodEntry findMethod(PhpClass cls, String name) {
        PhpClass.MethodEntry entry = cls.lookupMethod(name);
        if (entry != null) {
            return entry;
        }
        for (PhpClass iface : collectInterfaces(cls)) {
            entry = iface.lookupMethod(name);
            if (entry != null) {
                return entry;
            }
        }
        return null;
    }

    private static MethodState methodState(PhpClass reflected, PhpClass.MethodEntry entry) {        PhpClass declaring = entry.declaringClass();
        PhpFunctionRootNode root = entry.callTarget() == null ? null
                : PhpFunctionRootNode.of(entry.callTarget().getRootNode());
        PhpSignature signature = root != null && root.signature() != PhpSignature.EMPTY ? root.signature() : null;
        if (signature == null) {
            PhpClassTemplate.MethodDecl decl = findMethodDecl(declaring, PhpStrings.lower(entry.name()));
            if (decl != null) {
                signature = decl.signature();
            }
        }
        if (signature == null) {
            signature = PhpSignature.EMPTY;
        }
        boolean internal = declaring.template().sourceFile() == null && root == null;
        return new MethodState(reflected, entry, declaring, signature, root, internal);
    }

    private static PhpClassTemplate.MethodDecl findMethodDecl(PhpClass cls, String lowerName) {
        PhpClassTemplate.MethodDecl decl = cls.template().methods().get(lowerName);
        if (decl != null) {
            return decl;
        }
        for (PhpClass trait : cls.usedTraits()) {
            decl = findMethodDecl(trait, lowerName);
            if (decl != null) {
                return decl;
            }
        }
        return null;
    }

    private static int methodModifiers(MethodState state) {
        PhpClass.MethodEntry entry = state.entry();
        int mods = switch (entry.visibility()) {
            case PUBLIC -> 1;
            case PROTECTED -> 2;
            case PRIVATE -> 4;
        };
        if (entry.isStatic()) {
            mods |= 16;
        }
        if (entry.callTarget() == null) {
            mods |= 64;
        }
        if (entry.isFinal()) {
            mods |= 32;
        }
        return mods;
    }

    // ---------------------------------------------------------------- property metadata

    private record PropertyInfo(PhpClass reflected, PhpClass declaring, PhpClassTemplate.PropertyDecl decl) {
    }

    private static List<PropertyInfo> collectProperties(PhpClass cls) {
        List<PropertyInfo> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        collectClassProperties(cls, true, out, seen);
        return out;
    }

    private static void collectClassProperties(PhpClass cls, boolean own, List<PropertyInfo> out, Set<String> seen) {
        if (cls == null) {
            return;
        }
        if (own && cls.template().isEnum()) {
            out.add(new PropertyInfo(null, cls, new PhpClassTemplate.PropertyDecl(
                    "name", Visibility.PUBLIC, false, true, null, null, new PhpType.Named("string"),
                    List.of(), false)));
            if (cls.template().enumBackingType() != null) {
                out.add(new PropertyInfo(null, cls, new PhpClassTemplate.PropertyDecl(
                        "value", Visibility.PUBLIC, false, true, null, null,
                        cls.template().enumBackingType(), List.of(), false)));
            }
            seen.add("name");
            seen.add("value");
        }
        for (PhpClassTemplate.PropertyDecl decl : cls.template().properties()) {
            if (!own && decl.visibility() == Visibility.PRIVATE) {
                continue;
            }
            if (seen.add(decl.name())) {
                out.add(new PropertyInfo(null, cls, decl));
            }
        }
        for (PhpClass trait : cls.usedTraits()) {
            collectTraitProperties(trait, cls, own, out, seen);
        }
        collectClassProperties(cls.parent(), false, out, seen);
    }

    private static void collectTraitProperties(PhpClass trait, PhpClass using, boolean includePrivate,
                                               List<PropertyInfo> out, Set<String> seen) {
        for (PhpClassTemplate.PropertyDecl decl : trait.template().properties()) {
            if (!includePrivate && decl.visibility() == Visibility.PRIVATE) {
                continue;
            }
            if (seen.add(decl.name())) {
                out.add(new PropertyInfo(null, using, decl));
            }
        }
        for (PhpClass nested : trait.usedTraits()) {
            collectTraitProperties(nested, using, includePrivate, out, seen);
        }
    }

    private static PropertyInfo findProperty(ClassState state, String name) {
        for (PropertyInfo info : collectProperties(state.cls())) {
            if (info.decl().name().equals(name)) {
                return info;
            }
        }
        return null;
    }

    private static boolean hasDynamicProperty(ClassState state, String name) {
        return state.object() != null
                && DynamicObject.ContainsKeyNode.getUncached().execute(state.object(), name);
    }

    private static PhpObject propertyObject(PhpContext ctx, PhpClass reflected, PropertyInfo info) {
        return newReflectionObject(ctx, "ReflectionProperty",
                new PropertyState(reflected, info.declaring(), info.decl(), false, info.decl().name()));
    }

    private static int propertyModifiers(PhpClassTemplate.PropertyDecl decl) {
        return propertyModifiers(decl, null);
    }

    private static int propertyModifiers(PhpClassTemplate.PropertyDecl decl, PhpClass.InstanceProp prop) {
        int mods = switch (decl.visibility()) {
            case PUBLIC -> 1;
            case PROTECTED -> 2;
            case PRIVATE -> 4;
        };
        if (decl.isStatic()) {
            mods |= 16;
        }
        if (decl.isReadonly()) {
            mods |= 128;
        }
        Visibility set = prop != null ? prop.setVisibility() : decl.setVisibility();
        if (set == Visibility.PRIVATE) {
            mods |= 4096 | 32;
        } else if (set == Visibility.PROTECTED
                || (decl.isReadonly() && decl.visibility() == Visibility.PUBLIC)) {
            mods |= 2048;
        }
        boolean virtual = prop != null ? prop.virtual() : decl.virtual();
        if (virtual) {
            mods |= 512;
        }
        return mods;
    }

    private static boolean propertyHasDefault(PropertyInfo info) {
        PhpClassTemplate.PropertyDecl decl = info.decl();
        if (decl == null) {
            return false;
        }
        if (decl.hasDefault()) {
            return true;
        }
        if (decl.hasHooks() || decl.virtual()) {
            return false;
        }
        PhpClass.InstanceProp prop = info.declaring() == null ? null : info.declaring().findVisibleProp(decl.name());
        if (prop != null && (prop.hasHooks() || prop.virtual())) {
            return false;
        }
        return decl.type() == null && !isPromotedProperty(info);
    }

    /** A typed static property without a declared default holds the uninitialized sentinel until assigned. */
    private static boolean staticPropertyInitialized(PhpClass.StaticSlot slot, PropertyInfo info) {
        return slot.get() != PhpClass.UNINITIALIZED;
    }

    private static boolean isPromotedProperty(PropertyInfo info) {
        if (info.decl() == null || info.declaring() == null) {
            return false;
        }
        PhpClassTemplate.MethodDecl ctor = info.declaring().template().methods().get("__construct");
        if (ctor == null) {
            return false;
        }
        for (PhpParameter parameter : ctor.signature().parameters()) {
            if (parameter.promoted() && parameter.name().equals(info.decl().name())) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- constants

    private record ConstantInfo(PhpClass reflected, PhpClass declaring, PhpClassTemplate.ConstantDecl decl) {
    }

    private static List<ConstantInfo> collectConstants(PhpClass cls) {
        List<ConstantInfo> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        collectClassConstants(cls, true, out, seen);
        return out;
    }

    private static void collectClassConstants(PhpClass cls, boolean own, List<ConstantInfo> out, Set<String> seen) {
        if (cls == null) {
            return;
        }
        if (own && cls.template().isEnum()) {
            // An enum's cases come before its declared constants, as in getConstants().
            for (PhpClassTemplate.EnumCaseDecl enumCase : cls.template().enumCases()) {
                if (seen.add(enumCase.name())) {
                    out.add(new ConstantInfo(null, cls, new PhpClassTemplate.ConstantDecl(
                            enumCase.name(), Visibility.PUBLIC, false, null, null, null,
                            enumCase.attributes())));
                }
            }
        }
        for (PhpClassTemplate.ConstantDecl decl : cls.template().constants()) {
            if (!own && decl.visibility() == Visibility.PRIVATE) {
                continue;
            }
            if (seen.add(decl.name())) {
                out.add(new ConstantInfo(null, cls, decl));
            }
        }
        for (PhpClass trait : cls.usedTraits()) {
            collectTraitConstants(trait, cls, own, out, seen);
        }
        collectClassConstants(cls.parent(), false, out, seen);
        for (PhpClass iface : cls.interfaces()) {
            collectInterfaceConstants(iface, out, seen);
        }
    }

    private static void collectTraitConstants(PhpClass trait, PhpClass using, boolean includePrivate,
                                              List<ConstantInfo> out, Set<String> seen) {
        for (PhpClassTemplate.ConstantDecl decl : trait.template().constants()) {
            if (!includePrivate && decl.visibility() == Visibility.PRIVATE) {
                continue;
            }
            if (seen.add(decl.name())) {
                out.add(new ConstantInfo(null, using, decl));
            }
        }
        for (PhpClass nested : trait.usedTraits()) {
            collectTraitConstants(nested, using, includePrivate, out, seen);
        }
    }

    private static void collectInterfaceConstants(PhpClass iface, List<ConstantInfo> out, Set<String> seen) {
        for (PhpClassTemplate.ConstantDecl decl : iface.template().constants()) {
            if (seen.add(decl.name())) {
                out.add(new ConstantInfo(null, iface, decl));
            }
        }
        for (PhpClass parent : iface.interfaces()) {
            collectInterfaceConstants(parent, out, seen);
        }
    }

    private static ConstantInfo findConstant(PhpClass cls, String name) {
        for (ConstantInfo info : collectConstants(cls)) {
            if (info.decl().name().equals(name)) {
                return info;
            }
        }
        return null;
    }

    private static int constantModifiers(PhpClassTemplate.ConstantDecl decl) {
        int mods = switch (decl.visibility()) {
            case PUBLIC -> 1;
            case PROTECTED -> 2;
            case PRIVATE -> 4;
        };
        if (decl.isFinal()) {
            mods |= 32;
        }
        return mods;
    }

    // ---------------------------------------------------------------- parameter metadata

    private record ParameterInfo(PhpObject owner, PhpParameter parameter, int position, PhpClass declaringClass,
                                 PhpFunctionRootNode root, int requiredCount, boolean internal) {
    }

    private static PhpArray parametersForSignature(PhpContext ctx, PhpObject owner, PhpSignature signature,
                                                   PhpClass declaringClass, PhpFunctionRootNode root,
                                                   int requiredCount, boolean internal) {
        PhpArray out = new PhpArray();
        for (int i = 0; i < signature.parameters().size(); i++) {
            ParameterInfo info = new ParameterInfo(owner, signature.parameters().get(i), i, declaringClass, root,
                    requiredCount, internal);
            out.append(newReflectionObject(ctx, "ReflectionParameter",
                    new ParameterState(info.parameter(), info.position(), info.declaringClass(), info.root(),
                            info.requiredCount(), info.internal(), info.owner())));
        }
        return out;
    }

    private static PhpArray parametersForBuiltin(PhpContext ctx, PhpObject owner, Builtins.Def builtin) {
        PhpArray out = new PhpArray();
        Builtins.Def def = builtin;
        BuiltinParams.Param[] params = def == null ? null : BuiltinParams.forName(def.name());
        if (params == null) {
            return out;
        }
        int required = 0;
        for (int i = 0; i < params.length; i++) {
            if (!params[i].optional() && !params[i].hasDefault()) {
                required = i + 1;
            }
        }
        for (int i = 0; i < params.length; i++) {
            BuiltinParams.Param param = params[i];
            PhpParameter phpParam = new PhpParameter(param.name(), null, List.of(), false, param.variadic(),
                    false, param.hasDefault() ? param.defaultValue() : null, null);
            out.append(newReflectionObject(ctx, "ReflectionParameter",
                    new ParameterState(phpParam, i, null, null, required, true, owner)));
        }
        return out;
    }

    private static int signatureRequiredCount(PhpSignature signature) {
        int required = 0;
        List<PhpParameter> params = signature.parameters();
        for (int i = 0; i < params.size(); i++) {
            if (params.get(i).variadic()) {
                break;
            }
            if (!params.get(i).hasDefault()) {
                required = i + 1;
            }
        }
        return required;
    }

    private static boolean parameterHasDefault(ParameterState state) {
        PhpParameter param = state.parameter();
        if (!param.hasDefault()) {
            return false;
        }
        return state.position() >= state.requiredCount();
    }

    // ---------------------------------------------------------------- public name/class properties

    /** The public {@code string $name} (and {@code $class}) php declares on reflectors. */
    private static PhpClassTemplate.PropertyDecl stringProp(String name) {
        return new PhpClassTemplate.PropertyDecl(name, Visibility.PUBLIC, false, false, PhpNull.INSTANCE, null,
                new PhpType.Named("string"), List.of(), false, null, false, null, null, true);
    }

    private static List<PhpClassTemplate.PropertyDecl> nameProps() {
        return List.of(stringProp("name"));
    }

    private static List<PhpClassTemplate.PropertyDecl> classProps() {
        return List.of(stringProp("class"));
    }

    private static List<PhpClassTemplate.PropertyDecl> nameClassProps() {
        return List.of(stringProp("name"), stringProp("class"));
    }

    /** Sets the native state and fills the public {@code $name}/{@code $class} php shows for it. */
    private static void attach(PhpObject self, PhpNativeState state) {
        self.setNativeState(state);
        String name = null;
        String cls = null;
        switch (state) {
            case ClassState s -> name = s.cls().name();
            case FunctionState s -> name = s.name;
            case MethodState s -> {
                name = s.entry().name();
                cls = s.declaring().name();
            }
            case ParameterState s -> name = s.parameter().name();
            case PropertyState s -> {
                name = s.name();
                cls = (s.declaring() == null ? s.reflected() : s.declaring()).name();
            }
            case ConstantState s -> {
                name = s.decl().name();
                cls = s.declaring().name();
            }
            case EnumCaseRefState s -> {
                name = s.decl().name();
                cls = s.enumClass().name();
            }
            default -> {
                return;
            }
        }
        DynamicObject.PutNode put = DynamicObject.PutNode.getUncached();
        put.execute(self, "name", name);
        if (cls != null) {
            put.execute(self, "class", cls);
        }
    }

    // ---------------------------------------------------------------- reflection objects

    private static PhpObject newReflectionObject(PhpContext ctx, String className, PhpNativeState state) {
        PhpClass cls = ctx.lookupClass(className);
        if (cls == null) {
            throw new PhpError("Reflection class " + className + " is not available");
        }
        PhpObject object = new PhpObject(cls, ctx.nextObjectId());
        attach(object, state);
        return object;
    }

    private static PhpObject reflectionClassObject(PhpContext ctx, PhpClass cls, PhpObject object) {
        return newReflectionObject(ctx, "ReflectionClass", new ClassState(cls, object));
    }

    private static PhpObject reflectionMethodObject(PhpContext ctx, PhpClass reflected, PhpClass.MethodEntry entry) {
        return newReflectionObject(ctx, "ReflectionMethod", methodState(reflected, entry));
    }

    private static PhpObject methodObject(PhpContext ctx, MethodState state) {
        return newReflectionObject(ctx, "ReflectionMethod", state);
    }

    private static PhpObject propertyObjectFromState(PhpContext ctx, PropertyState state) {
        return newReflectionObject(ctx, "ReflectionProperty", state);
    }

    private static PhpObject constantObject(PhpContext ctx, PhpClass reflected, ConstantInfo info) {
        boolean enumCase = info.declaring().template().isEnum()
                && info.declaring().template().enumCase(info.decl().name()) != null;
        return newReflectionObject(ctx, "ReflectionClassConstant",
                new ConstantState(reflected, info.declaring(), info.decl(), enumCase));
    }

    private static PhpObject functionObject(PhpContext ctx, FunctionState state) {
        return newReflectionObject(ctx, "ReflectionFunction", state);
    }

    // ---------------------------------------------------------------- ReflectionFunction and ReflectionMethod

    private static void buildReflectionFunctionAbstract(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        addAbstractMethod(language, methods, "__construct");
        addMethod(language, methods, "getName", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> functionName(self));
        addMethod(language, methods, "getShortName", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> shortName(functionName(self)));
        addMethod(language, methods, "getNamespaceName", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> namespaceName(functionName(self)));
        addMethod(language, methods, "inNamespace", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> !namespaceName(functionName(self)).isEmpty());
        addMethod(language, methods, "getParameters", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            if (self.nativeState() instanceof FunctionState state && state.builtin != null) {
                return parametersForBuiltin(ctx, self, state.builtin);
            }
            PhpSignature signature = functionSignature(self);
            return parametersForSignature(ctx, self, signature, functionDeclaringClass(self), functionRoot(self),
                    functionRequiredCount(self), functionInternal(self));
        });
        addMethod(language, methods, "getNumberOfParameters", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> (long) functionParameterCount(self));
        addMethod(language, methods, "getNumberOfRequiredParameters", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> (long) functionRequiredCount(self));
        addMethod(language, methods, "getReturnType", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PhpType type = functionReturnType(self);
            return type == null ? PhpNull.INSTANCE : typeObject(ctx, type);
        });
        addMethod(language, methods, "hasReturnType", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> functionReturnType(self) != null);
        addMethod(language, methods, "isVariadic", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> functionVariadic(self));
        addMethod(language, methods, "returnsReference", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> functionReturnsReference(self));
        addMethod(language, methods, "getAttributes", Visibility.PUBLIC, false, false, (ctx, self, args) ->
                attributes(ctx, functionSignature(self).attributes(), args, functionAttributeTarget(self)));
        addMethod(language, methods, "isInternal", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> functionInternal(self));
        addMethod(language, methods, "isUserDefined", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> !functionInternal(self));
        addMethod(language, methods, "getFileName", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            String file = functionFileName(self);
            return file == null ? Boolean.FALSE : file;
        });
        addMethod(language, methods, "getStartLine", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            int line = functionStartLine(self);
            return line < 0 ? Boolean.FALSE : (long) line;
        });
        addMethod(language, methods, "getEndLine", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            int line = functionEndLine(self);
            return line < 0 ? Boolean.FALSE : (long) line;
        });
        addMethod(language, methods, "getStaticVariables", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> staticVariables(ctx, self));
        addMethod(language, methods, "getDocComment", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> Boolean.FALSE);
        addMethod(language, methods, "isClosure", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> self.nativeState() instanceof FunctionState state && state.closure);
        addMethod(language, methods, "isStatic", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> functionIsStatic(self));
        addMethod(language, methods, "getClosureCalledClass", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PhpClass cls = functionClosureCalledClass(self);
            return cls == null ? PhpNull.INSTANCE : reflectionClassObject(ctx, cls, null);
        });
        addMethod(language, methods, "getClosureUsedVariables", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> closureUsedVariables(self));
        addMethod(language, methods, "getClosureThis", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            if (self.nativeState() instanceof FunctionState state && state.callable != null
                    && state.callable.boundThis() instanceof PhpObject object) {
                return object;
            }
            if (self.nativeState() instanceof MethodState state && !state.entry().isStatic()) {
                return PhpNull.INSTANCE;
            }
            return PhpNull.INSTANCE;
        });
        addMethod(language, methods, "getClosureScopeClass", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PhpClass cls = functionScopeClass(ctx, self);
            return cls == null ? PhpNull.INSTANCE : reflectionClassObject(ctx, cls, null);
        });
        addMethod(language, methods, "setAccessible", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> PhpNull.INSTANCE);
        addMethod(language, methods, "__toString", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> functionName(self));
        register(out, new PhpClassTemplate(language.nextTemplateId(), "ReflectionFunctionAbstract", false, false,
                true, false, false, null, List.of("Stringable", "Reflector"), List.of(), nameProps(), List.of(),
                methods));
    }

    private static void buildReflectionFunction(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        addMethod(language, methods, "__construct", Visibility.PUBLIC, false, false,
                List.of("function"), (ctx, self, args) -> {
            requireArgCount("ReflectionFunction::__construct", args, 1, 1);
            FunctionState state = functionStateFor(ctx, args[0]);
            attach(self, state);
            return PhpNull.INSTANCE;
        });
        addMethod(language, methods, "invoke", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            FunctionState state = functionState(self);
            dev.phpruntime.runtime.ForwardedNamedArgs forwarded = ctx.takeForwardedNamed();
            return invokeCallable(ctx, state, positionalArgs(args),
                    forwarded == null ? new LinkedHashMap<>() : new LinkedHashMap<>(forwarded.values()));
        });
        addMethod(language, methods, "invokeArgs", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            requireArgCount("ReflectionFunction::invokeArgs", args, 1, 1);
            if (!(args[0] instanceof PhpArray values)) {
                throw new PhpError(PhpError.TYPE_ERROR,
                        "ReflectionFunction::invokeArgs(): Argument #1 ($args) must be of type array, "
                                + PhpOps.typeName(args[0]) + " given", null);
            }
            return invokeCallable(ctx, functionState(self), values, "ReflectionFunction::invokeArgs");
        });
        addMethod(language, methods, "getClosure", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            FunctionState state = functionState(self);
            if (state.closureObject != null) {
                return state.closureObject;
            }
            return PhpCallable.toClosure(ctx, state.name, null, null);
        });
        addMethod(language, methods, "isAnonymous", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> functionIsAnonymous(self));
        register(out, new PhpClassTemplate(language.nextTemplateId(), "ReflectionFunction", false, false, false,
                false, false, "ReflectionFunctionAbstract", List.of(), List.of(), List.of(), List.of(), methods));
    }

    private static void buildReflectionMethod(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        addMethod(language, methods, "__construct", Visibility.PUBLIC, false, false,
                List.of("objectOrMethod", "method"), (ctx, self, args) -> {
            requireArgCount("ReflectionMethod::__construct", args, 1, 2);
            PhpClass cls;
            String methodName;
            PhpObject object = null;
            if (args.length == 1) {
                String qualified = PhpOps.toStr(args[0]);
                int sep = qualified.indexOf("::");
                if (sep < 0) {
                    throw reflectionError(
                            "ReflectionMethod::__construct(): Argument #1 ($objectOrMethod) must be a valid method name");
                }
                cls = ctx.lookupOrAutoloadClass(qualified.substring(0, sep));
                if (cls == null) {
                    throw reflectionError("Class \"" + qualified.substring(0, sep) + "\" does not exist");
                }
                methodName = qualified.substring(sep + 2);
            } else {
                if (args[0] instanceof PhpObject instance) {
                    object = instance;
                    cls = instance.phpClass();
                } else {
                    cls = ctx.lookupOrAutoloadClass(PhpOps.toStr(args[0]));
                    if (cls == null) {
                        throw reflectionError("Class \"" + PhpOps.toStr(args[0]) + "\" does not exist");
                    }
                }
                methodName = PhpOps.toStr(args[1]);
            }
            PhpClass.MethodEntry entry = findMethod(cls, methodName);
            if (entry == null) {
                throw reflectionError("Method " + cls.name() + "::" + methodName + "() does not exist");
            }
            MethodState state = methodState(cls, entry);
            attach(self, state);
            return PhpNull.INSTANCE;
        });
        addMethod(language, methods, "invoke", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            MethodState state = methodState(self);
            Object object = args.length > 0 ? args[0] : PhpNull.INSTANCE;
            List<Object> values = new ArrayList<>();
            for (int i = 1; i < args.length; i++) {
                values.add(args[i]);
            }
            dev.phpruntime.runtime.ForwardedNamedArgs forwarded = ctx.takeForwardedNamed();
            return invokeMethod(ctx, state, object, values,
                    forwarded == null ? new LinkedHashMap<>() : new LinkedHashMap<>(forwarded.values()));
        });
        addMethod(language, methods, "invokeArgs", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            requireArgCount("ReflectionMethod::invokeArgs", args, 2, 2);
            if (!(args[1] instanceof PhpArray values)) {
                throw new PhpError(PhpError.TYPE_ERROR,
                        "ReflectionMethod::invokeArgs(): Argument #2 ($args) must be of type array, "
                                + PhpOps.typeName(args[1]) + " given", null);
            }
            return invokeMethod(ctx, methodState(self), args[0], values);
        });
        addMethod(language, methods, "getClosure", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            MethodState state = methodState(self);
            Object object = args.length > 0 ? args[0] : PhpNull.INSTANCE;
            PhpClass.MethodEntry entry = state.entry();
            Object thisArg;
            if (entry.isStatic()) {
                thisArg = PhpNull.INSTANCE;
            } else {
                if (object == PhpNull.INSTANCE || object == null) {
                    throw new PhpError(PhpError.VALUE_ERROR,
                            "ReflectionMethod::getClosure(): Argument #1 ($object) cannot be null for non-static methods",
                            null);
                }
                if (!(object instanceof PhpObject instance)
                        || !instance.phpClass().isInstanceOf(entry.declaringClass())) {
                    throw reflectionError("Given object is not an instance of the class this method was declared in");
                }
                thisArg = instance;
            }
            PhpClosure closure = PhpClosure.method(entry.callTarget(), thisArg,
                    thisArg instanceof PhpObject instance ? instance.phpClass() : state.reflected(),
                    entry.fromTrait() ? entry.declaringClass() : null, entry);
            return ctx.newClosure(closure);
        });
        addMethod(language, methods, "isStatic", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> methodState(self).entry().isStatic());
        addMethod(language, methods, "isPublic", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> methodState(self).entry().visibility() == Visibility.PUBLIC);
        addMethod(language, methods, "isProtected", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> methodState(self).entry().visibility() == Visibility.PROTECTED);
        addMethod(language, methods, "isPrivate", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> methodState(self).entry().visibility() == Visibility.PRIVATE);
        addMethod(language, methods, "isAbstract", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> methodState(self).entry().callTarget() == null);
        addMethod(language, methods, "isFinal", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> methodState(self).entry().isFinal());
        addMethod(language, methods, "isConstructor", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> "__construct".equalsIgnoreCase(methodState(self).entry().name()));
        addMethod(language, methods, "isDestructor", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> "__destruct".equalsIgnoreCase(methodState(self).entry().name()));
        addMethod(language, methods, "getDeclaringClass", Visibility.PUBLIC, false, false, (ctx, self, args) ->
                reflectionClassObject(ctx, methodState(self).declaring(), null));
        addMethod(language, methods, "getModifiers", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> (long) methodModifiers(methodState(self)));
        register(out, new PhpClassTemplate(language.nextTemplateId(), "ReflectionMethod", false, false, false,
                false, false, "ReflectionFunctionAbstract", List.of(), List.of(), classProps(), List.of(), methods));

        // Constants shared with ReflectionMethod.
        addConstantsTo(out, "reflectionmethod", List.of(
                Map.entry("IS_STATIC", 16L), Map.entry("IS_PUBLIC", 1L), Map.entry("IS_PROTECTED", 2L),
                Map.entry("IS_PRIVATE", 4L), Map.entry("IS_ABSTRACT", 64L), Map.entry("IS_FINAL", 32L)));
    }

    private static void addConstantsTo(Map<String, PhpClassTemplate> out, String lowerName,
                                       List<Map.Entry<String, Long>> values) {
        PhpClassTemplate old = out.get(lowerName);
        List<PhpClassTemplate.ConstantDecl> constants = new ArrayList<>(old.constants());
        for (Map.Entry<String, Long> entry : values) {
            constant(constants, entry.getKey(), entry.getValue(), Visibility.PUBLIC, false);
        }
        out.put(lowerName, new PhpClassTemplate(old.id(), old.name(), old.isInterface(), old.isTrait(),
                old.isAbstract(), old.isFinal(), old.isAnonymous(), old.parentName(),
                old.interfaceNames(), old.traitUses(), old.properties(), constants,
                old.methods(), old.methodSignatures(), old.attributes(), old.isEnum(), old.enumBackingType(),
                old.enumCases()));
        if (old.sourceFile() != null) {
            out.get(lowerName).withSource(old.sourceFile(), old.startLine(), old.endLine());
        }
    }

    private static FunctionState functionState(PhpObject self) {
        if (self != null && self.nativeState() instanceof FunctionState state) {
            return state;
        }
        throw new PhpError("ReflectionFunction object is not initialized");
    }

    private static MethodState methodState(PhpObject self) {
        if (self != null && self.nativeState() instanceof MethodState state) {
            return state;
        }
        throw new PhpError("ReflectionMethod object is not initialized");
    }

    private static String functionName(PhpObject self) {
        if (self.nativeState() instanceof FunctionState state) {
            return state.name;
        }
        if (self.nativeState() instanceof MethodState state) {
            return state.entry().name();
        }
        return "";
    }

    private static PhpSignature functionSignature(PhpObject self) {
        if (self.nativeState() instanceof FunctionState state) {
            return state.signature;
        }
        if (self.nativeState() instanceof MethodState state) {
            return state.signature();
        }
        return PhpSignature.EMPTY;
    }

    private static PhpFunctionRootNode functionRoot(PhpObject self) {
        if (self.nativeState() instanceof FunctionState state) {
            return state.root;
        }
        if (self.nativeState() instanceof MethodState state) {
            return state.root();
        }
        return null;
    }

    private static PhpClass functionDeclaringClass(PhpObject self) {
        if (self.nativeState() instanceof MethodState state) {
            return state.declaring();
        }
        return null;
    }

    private static boolean functionInternal(PhpObject self) {
        if (self.nativeState() instanceof FunctionState state) {
            return state.internal;
        }
        if (self.nativeState() instanceof MethodState state) {
            return state.internal();
        }
        return false;
    }

    private static int functionAttributeTarget(PhpObject self) {
        if (self.nativeState() instanceof MethodState) {
            return TARGET_METHOD;
        }
        if (self.nativeState() instanceof FunctionState state && state.callable != null
                && state.callable.kind() == PhpCallable.Kind.METHOD) {
            return TARGET_METHOD;
        }
        return TARGET_FUNCTION;
    }

    private static int functionParameterCount(PhpObject self) {
        if (self.nativeState() instanceof FunctionState state && state.builtin != null) {
            BuiltinParams.Param[] params = BuiltinParams.forName(state.builtin.name());
            return params == null ? 0 : params.length;
        }
        return functionSignature(self).parameters().size();
    }

    private static int functionRequiredCount(PhpObject self) {
        if (self.nativeState() instanceof FunctionState state && state.builtin != null) {
            BuiltinParams.Param[] params = BuiltinParams.forName(state.builtin.name());
            if (params != null) {
                int required = 0;
                for (int i = 0; i < params.length; i++) {
                    if (!params[i].optional() && !params[i].hasDefault()) {
                        required = i + 1;
                    }
                }
                return required;
            }
        }
        PhpFunctionRootNode root = functionRoot(self);
        if (root != null) {
            return root.requiredArgs();
        }
        return signatureRequiredCount(functionSignature(self));
    }

    private static PhpType functionReturnType(PhpObject self) {
        return functionSignature(self).returnType();
    }

    private static boolean functionVariadic(PhpObject self) {
        if (self.nativeState() instanceof FunctionState state && state.builtin != null) {
            BuiltinParams.Param[] params = BuiltinParams.forName(state.builtin.name());
            return params != null && params.length > 0 && params[params.length - 1].variadic();
        }
        PhpFunctionRootNode root = functionRoot(self);
        if (root != null) {
            return root.isVariadic();
        }
        List<PhpParameter> params = functionSignature(self).parameters();
        return !params.isEmpty() && params.get(params.size() - 1).variadic();
    }

    private static boolean functionReturnsReference(PhpObject self) {
        PhpFunctionRootNode root = functionRoot(self);
        return root != null && root.returnsRef();
    }

    private static int functionEndLine(PhpObject self) {
        return functionSignature(self).endLine();
    }

    private static String functionFileName(PhpObject self) {
        PhpFunctionRootNode root = functionRoot(self);
        if (root != null && root.source() != null) {
            return root.source().getPath();
        }
        return functionSignature(self).sourceFile();
    }

    private static int functionStartLine(PhpObject self) {
        PhpFunctionRootNode root = functionRoot(self);
        if (root != null) {
            return root.line();
        }
        return functionSignature(self).startLine();
    }

    private static PhpClass functionScopeClass(PhpContext ctx, PhpObject self) {
        if (self.nativeState() instanceof MethodState state) {
            return state.declaring();
        }
        if (self.nativeState() instanceof FunctionState state && state.callable != null) {
            if (state.callable.kind() == PhpCallable.Kind.CLOSURE) {
                return state.callable.traitSelf() != null ? state.callable.traitSelf()
                        : state.callable.calledClass();
            }
            if (state.callable.kind() == PhpCallable.Kind.METHOD) {
                if (state.callable.traitSelf() != null) {
                    return state.callable.traitSelf();
                }
                String declaring = state.root == null ? null : state.root.declaringClass();
                PhpClass scope = declaring == null ? null : ctx.lookupClass(declaring);
                return scope != null ? scope : state.callable.calledClass();
            }
            return null;
        }
        return null;
    }

    private static boolean functionIsStatic(PhpObject self) {
        if (self.nativeState() instanceof FunctionState state) {
            return state.root != null && state.root.isStaticMethod();
        }
        if (self.nativeState() instanceof MethodState state) {
            return state.entry().isStatic();
        }
        return false;
    }

    private static PhpClass functionClosureCalledClass(PhpObject self) {
        if (self.nativeState() instanceof FunctionState state && state.closure && state.callable != null) {
            return state.callable.calledClass();
        }
        return null;
    }

    private static boolean functionIsAnonymous(PhpObject self) {
        if (self.nativeState() instanceof FunctionState state && state.closure) {
            return state.root != null && state.root.isClosure();
        }
        return false;
    }

    private static PhpArray closureUsedVariables(PhpObject self) {
        PhpArray out = new PhpArray();
        if (!(self.nativeState() instanceof FunctionState state) || !state.closure
                || state.closureObject == null || state.closureObject.closure() == null) {
            return out;
        }
        PhpClosure closure = state.closureObject.closure();
        if (closure.kind() != PhpClosure.Kind.CLOSURE) {
            return out;
        }
        Object[] captures = closure.captures();
        String[] names = state.root == null ? null : state.root.captureNames();
        if (captures == null || names == null) {
            return out;
        }
        for (int i = 0; i < captures.length && i < names.length; i++) {
            if (names[i] == null) {
                continue;
            }
            Object value = captures[i];
            out.put(names[i], value instanceof PhpReference ? value
                    : PhpOps.copyOnAssign(value == null ? PhpNull.INSTANCE : value));
        }
        return out;
    }

    private static PhpArray staticVariables(PhpContext ctx, PhpObject self) {
        Object owner = null;
        if (self.nativeState() instanceof FunctionState state) {
            owner = state.closureObject != null ? state.closureObject : state.root;
        } else if (self.nativeState() instanceof MethodState state) {
            owner = state.root();
        }
        PhpArray out = new PhpArray();
        Map<String, PhpReference> locals = owner == null ? null : ctx.staticLocalsOf(owner);
        if (locals != null) {
            for (Map.Entry<String, PhpReference> entry : locals.entrySet()) {
                Object value = entry.getValue().getValue();
                out.put(entry.getKey(), PhpOps.copyOnAssign(value == null ? PhpNull.INSTANCE : value));
            }
        }
        return out;
    }

    private static FunctionState functionStateFor(PhpContext ctx, Object value) {
        if (value instanceof PhpObject object) {
            if (object.closure() == null) {
                throw new PhpError(PhpError.TYPE_ERROR,
                        "ReflectionFunction::__construct(): Argument #1 ($function) must be of type Closure|string, "
                                + PhpOps.typeName(value) + " given", null);
            }
            PhpClosure closure = object.closure();
            PhpCallable callable = PhpCallable.resolveOrNull(ctx, object);
            PhpFunctionRootNode root = closure.callTarget() == null ? null
                    : PhpFunctionRootNode.of(closure.callTarget().getRootNode());
            PhpSignature signature = root != null && root.signature() != PhpSignature.EMPTY ? root.signature()
                    : PhpSignature.EMPTY;
            String name;
            if (closure.kind() == PhpClosure.Kind.CLOSURE) {
                name = root == null ? "{closure}" : root.qualifiedCallableName();
            } else if (closure.kind() == PhpClosure.Kind.BUILTIN) {
                name = closure.builtin().name();
            } else if (root != null) {
                name = root.rawFunctionName();
            } else if (closure.callTarget() != null
                    && closure.callTarget().getRootNode() instanceof PhpExceptions.BuiltinMethodRootNode builtinMethod) {
                name = builtinMethod.getName();
            } else {
                name = "{closure}";
            }
            boolean internal = closure.kind() == PhpClosure.Kind.BUILTIN
                    || (closure.callTarget() != null
                    && closure.callTarget().getRootNode() instanceof PhpExceptions.BuiltinMethodRootNode);
            return new FunctionState(name, callable, object, root, closure.builtin(), signature, true, internal);
        }
        if (!(value instanceof String) && !(value instanceof PhpObject)) {
            throw new PhpError(PhpError.TYPE_ERROR,
                    "ReflectionFunction::__construct(): Argument #1 ($function) must be of type Closure|string, "
                            + PhpOps.typeName(value) + " given", null);
        }
        String name = PhpOps.toStr(value);
        String plain = name.startsWith("\\") ? name.substring(1) : name;
        PhpFunction function = ctx.lookupFunction(plain);
        if (function != null) {
            PhpFunctionRootNode root = PhpFunctionRootNode.of(function.callTarget().getRootNode());
            PhpSignature signature = root != null && root.signature() != PhpSignature.EMPTY ? root.signature()
                    : PhpSignature.EMPTY;
            PhpCallable callable = PhpCallable.resolveOrNull(ctx, function.name());
            return new FunctionState(function.name(), callable, null, root, null, signature, false, false);
        }
        Builtins.Def builtin = Builtins.lookup(plain);
        if (builtin != null) {
            PhpCallable callable = PhpCallable.resolveOrNull(ctx, builtin.name());
            return new FunctionState(builtin.name(), callable, null, null, builtin, PhpSignature.EMPTY, false, true);
        }
        throw reflectionError("Function " + name + "() does not exist");
    }

    // ---------------------------------------------------------------- ReflectionParameter

    private static void buildReflectionParameter(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        addMethod(language, methods, "getName", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> parameterState(self).parameter().name());
        addMethod(language, methods, "getPosition", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> (long) parameterState(self).position());
        addMethod(language, methods, "getType", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PhpType type = parameterState(self).parameter().type();
            return type == null ? PhpNull.INSTANCE : typeObject(ctx, type);
        });
        addMethod(language, methods, "hasType", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> parameterState(self).parameter().type() != null);
        addMethod(language, methods, "allowsNull", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            ParameterState state = parameterState(self);
            PhpType type = state.parameter().type();
            if (type == null) {
                return true;
            }
            if (typeAllowsNull(type)) {
                return true;
            }
            return parameterHasDefault(state) && parameterState(self).parameter().defaultValue() == PhpNull.INSTANCE;
        });
        addMethod(language, methods, "isOptional", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> parameterState(self).parameter().variadic() || parameterHasDefault(parameterState(self)));
        addMethod(language, methods, "isDefaultValueAvailable", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> parameterHasDefault(parameterState(self)));
        addMethod(language, methods, "getDefaultValue", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            ParameterState state = parameterState(self);
            if (!parameterHasDefault(state)) {
                throw reflectionError("Internal error: Failed to retrieve the default value");
            }
            return evaluateDefault(ctx, state);
        });
        addMethod(language, methods, "isDefaultValueConstant", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            ParameterState state = parameterState(self);
            if (!parameterHasDefault(state)) {
                throw reflectionError("Internal error: Failed to retrieve the default value");
            }
            return state.parameter().defaultConstantName() != null;
        });
        addMethod(language, methods, "getDefaultValueConstantName", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            ParameterState state = parameterState(self);
            if (!parameterHasDefault(state)) {
                throw reflectionError("Internal error: Failed to retrieve the default value");
            }
            String name = state.parameter().defaultConstantName();
            return name == null ? PhpNull.INSTANCE : name;
        });
        addMethod(language, methods, "isVariadic", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> parameterState(self).parameter().variadic());
        addMethod(language, methods, "isPassedByReference", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> parameterState(self).parameter().byRef());
        addMethod(language, methods, "canBePassedByValue", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> !parameterState(self).parameter().byRef());
        addMethod(language, methods, "isPromoted", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> parameterState(self).parameter().promoted());
        addMethod(language, methods, "getAttributes", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> attributes(ctx, parameterState(self).parameter().attributes(), args,
                        TARGET_PARAMETER));
        addMethod(language, methods, "getDeclaringFunction", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> parameterState(self).declaringFunction());
        addMethod(language, methods, "getDeclaringClass", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PhpClass cls = parameterState(self).declaringClass();
            return cls == null ? PhpNull.INSTANCE : reflectionClassObject(ctx, cls, null);
        });
        addMethod(language, methods, "getClass", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PhpType type = parameterState(self).parameter().type();
            String name = null;
            if (type instanceof PhpType.Union union) {
                String only = null;
                int classes = 0;
                for (PhpType member : union.members()) {
                    if (member instanceof PhpType.Named named && !typeIsBuiltin(member)) {
                        only = named.name();
                        classes++;
                    }
                }
                if (classes == 1) {
                    name = only;
                }
            } else {
                if (type instanceof PhpType.Nullable nullable) {
                    type = nullable.inner();
                }
                if (type instanceof PhpType.Named named && !typeIsBuiltin(type)) {
                    name = named.name();
                }
            }
            if (name == null) {
                return PhpNull.INSTANCE;
            }
            PhpClass cls = ctx.lookupOrAutoloadClass(name);
            return cls == null ? PhpNull.INSTANCE : reflectionClassObject(ctx, cls, null);
        });
        addMethod(language, methods, "__toString", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> parameterState(self).parameter().name());
        register(out, new PhpClassTemplate(language.nextTemplateId(), "ReflectionParameter", false, false, false,
                false, false, null, List.of("Stringable", "Reflector"), List.of(), nameProps(), List.of(), methods));
    }

    private static ParameterState parameterState(PhpObject self) {
        if (self != null && self.nativeState() instanceof ParameterState state) {
            return state;
        }
        throw new PhpError("ReflectionParameter object is not initialized");
    }

    private static Object evaluateDefault(PhpContext ctx, ParameterState state) {
        PhpParameter parameter = state.parameter();
        if (parameter.defaultTarget() == null) {
            return PhpOps.copyOnAssign(parameter.defaultValue() == null ? PhpNull.INSTANCE : parameter.defaultValue());
        }
        Object value = evalTarget(parameter.defaultTarget(), state.declaringClass());
        return PhpOps.copyOnAssign(value == null ? PhpNull.INSTANCE : value);
    }

    private static Object evalTarget(RootCallTarget target, PhpClass selfClass) {
        if (target == null) {
            return PhpNull.INSTANCE;
        }
        if (target.getRootNode() instanceof dev.phpruntime.bytecode.PhpBytecodeRootNode bytecode
                && bytecode.hasTraitSelf()) {
            return target.call(selfClass);
        }
        return target.call();
    }

    // ---------------------------------------------------------------- ReflectionProperty

    private static void buildReflectionProperty(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        addMethod(language, methods, "__construct", Visibility.PUBLIC, false, false,
                List.of("class", "property"), (ctx, self, args) -> {
            requireArgCount("ReflectionProperty::__construct", args, 2, 2);
            ClassState clsState;
            PhpObject object = null;
            PhpClass cls;
            if (args[0] instanceof PhpObject instance) {
                object = instance;
                cls = instance.phpClass();
                clsState = new ClassState(cls, object);
            } else {
                cls = ctx.lookupOrAutoloadClass(PhpOps.toStr(args[0]));
                if (cls == null) {
                    throw reflectionError("Class \"" + PhpOps.toStr(args[0]) + "\" does not exist");
                }
                clsState = new ClassState(cls, null);
            }
            String name = PhpOps.toStr(args[1]);
            PropertyInfo info = findProperty(clsState, name);
            if (info == null) {
                if (hasDynamicProperty(clsState, name)) {
                    attach(self, new PropertyState(cls, null, null, true, name));
                    return PhpNull.INSTANCE;
                }
                throw reflectionError("Property " + cls.name() + "::$" + name + " does not exist");
            }
            attach(self, new PropertyState(cls, info.declaring(), info.decl(), false, name));
            return PhpNull.INSTANCE;
        });
        addMethod(language, methods, "getName", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> propertyState(self).name());
        addMethod(language, methods, "getValue", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PropertyState state = propertyState(self);
            if (state.dynamic()) {
                PhpObject object = requireInstanceObject(args, 0, "ReflectionProperty::getValue");
                return getObjectProperty(object, state.dynamicName());
            }
            if (state.decl().isStatic()) {
                PhpClass.StaticSlot slot = state.declaring().lookupStaticProperty(ctx, state.name());
                if (slot == null) {
                    return PhpNull.INSTANCE;
                }
                if (!staticPropertyInitialized(slot,
                        new PropertyInfo(state.reflected(), state.declaring(), state.decl()))) {
                    throw new PhpError("Typed static property " + state.declaring().name() + "::$" + state.name()
                            + " must not be accessed before initialization");
                }
                return slot.get();
            }
            PhpObject object = requireInstanceObject(args, 0, "ReflectionProperty::getValue");
            return getInstanceProperty(ctx, object, state);
        });
        addMethod(language, methods, "hasHooks", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PropertyState state = propertyState(self);
            PhpClass.InstanceProp prop = effectiveInstanceProp(state);
            return prop != null && prop.hasHooks();
        });
        addMethod(language, methods, "getHooks", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PropertyState state = propertyState(self);
            PhpArray hooks = new PhpArray();
            PhpClass.InstanceProp prop = effectiveInstanceProp(state);
            if (prop != null) {
                PhpClass.PropertyHook get = prop.getHook();
                if (get != null) {
                    hooks.put("get", reflectionMethodObject(ctx, state, prop, get));
                }
                PhpClass.PropertyHook set = prop.setHook();
                if (set != null) {
                    hooks.put("set", reflectionMethodObject(ctx, state, prop, set));
                }
            }
            return hooks;
        });
        addMethod(language, methods, "getHook", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PropertyState state = propertyState(self);
            if (args.length < 1 || !(args[0] instanceof PhpObject arg)
                    || !(arg.nativeState() instanceof EnumCaseState enumCase)
                    || !enumCase.enumName().equalsIgnoreCase("PropertyHookType")) {
                throw new PhpError(PhpError.TYPE_ERROR, "ReflectionProperty::getHook(): Argument #1 ($type)"
                        + " must be of type PropertyHookType, " + PhpOps.typeName(args.length == 0 ? null : args[0])
                        + " given", null);
            }
            PhpClass.InstanceProp prop = effectiveInstanceProp(state);
            if (prop == null) {
                return PhpNull.INSTANCE;
            }
            String want = enumCase.caseName().toLowerCase();
            PhpClass.PropertyHook hook = want.equals("get") ? prop.getHook() : prop.setHook();
            return hook == null ? PhpNull.INSTANCE : reflectionMethodObject(ctx, state, prop, hook);
        });
        addMethod(language, methods, "isVirtual", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PhpClass.InstanceProp prop = effectiveInstanceProp(propertyState(self));
            return prop != null && prop.virtual();
        });
        addMethod(language, methods, "isPrivateSet", Visibility.PUBLIC, false, false, (ctx, self, args) ->
                effectiveSetVisibility(propertyState(self)) == Visibility.PRIVATE);
        addMethod(language, methods, "isProtectedSet", Visibility.PUBLIC, false, false, (ctx, self, args) ->
                effectiveSetVisibility(propertyState(self)) == Visibility.PROTECTED);
        addMethod(language, methods, "getSettableType", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PropertyState state = propertyState(self);
            PhpClass.InstanceProp prop = effectiveInstanceProp(state);
            if (prop != null && prop.virtual() && prop.setHook() == null) {
                return typeObject(ctx, new PhpType.Named("never"));
            }
            PhpType type = state.decl() == null ? null : state.decl().type();
            return type == null ? PhpNull.INSTANCE : typeObject(ctx, type);
        });
        addMethod(language, methods, "setValue", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PropertyState state = propertyState(self);
            if (state.dynamic()) {
                requireArgCount("ReflectionProperty::setValue", args, 2, 2);
                PhpObject object = requireInstanceObject(args, 0, "ReflectionProperty::setValue");
                DynamicObject.PutNode.getUncached().execute(object, state.dynamicName(),
                        PhpOps.copyOnAssign(args[1]));
                return PhpNull.INSTANCE;
            }
            if (state.decl().writeBlocked()) {
                throw reflectionError("Cannot set read-only property " + state.reflected().name()
                        + "::$" + state.name());
            }
            if (state.decl().isStatic()) {
                Object value;
                if (args.length == 1) {
                    value = args[0];
                } else if (args.length == 2) {
                    value = args[1];
                } else {
                    throw new PhpError(PhpError.ARGUMENT_COUNT,
                            "ReflectionProperty::setValue() expects 1 or 2 arguments, " + args.length + " given", null);
                }
                PhpClass.StaticSlot slot = state.declaring().lookupStaticProperty(ctx, state.name());
                if (slot == null) {
                    throw reflectionError("Property " + state.declaring().name() + "::$" + state.name() + " does not exist");
                }
                slot.set(PhpOps.copyOnAssign(value));
                return PhpNull.INSTANCE;
            }
            requireArgCount("ReflectionProperty::setValue", args, 2, 2);
            PhpObject object = requireInstanceObject(args, 0, "ReflectionProperty::setValue");
            if (state.decl().isReadonly()) {
                Object current = rawInstanceProperty(ctx, object, state);
                boolean initialized = current != PhpClass.UNINITIALIZED;
                if (initialized) {
                    throw new PhpError("Cannot modify readonly property "
                            + state.declaring().name() + "::$" + state.name());
                }
            }
            setInstanceProperty(ctx, object, state, args[1]);
            return PhpNull.INSTANCE;
        });
        addMethod(language, methods, "setRawValue", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> setRawPropertyValue(ctx, propertyState(self), args, false));
        addMethod(language, methods, "setRawValueWithoutLazyInitialization", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> setRawPropertyValue(ctx, propertyState(self), args, true));
        addMethod(language, methods, "isAbstract", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PropertyState state = propertyState(self);
            if (state.dynamic()) {
                return false;
            }
            PhpClass.InstanceProp prop = effectiveInstanceProp(state);
            if (prop != null) {
                return (prop.getHook() != null && prop.getHook().isAbstract())
                        || (prop.setHook() != null && prop.setHook().isAbstract());
            }
            PhpClassTemplate.PropertyDecl decl = state.decl();
            return decl != null && ((decl.getHook() != null && decl.getHook().isAbstract())
                    || (decl.setHook() != null && decl.setHook().isAbstract()));
        });
        addMethod(language, methods, "isInitialized", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PropertyState state = propertyState(self);
            if (state.decl() != null && state.decl().isStatic()) {
                PhpClass.StaticSlot slot = state.declaring().lookupStaticProperty(ctx, state.name());
                return slot != null && staticPropertyInitialized(slot,
                        new PropertyInfo(state.reflected(), state.declaring(), state.decl()));
            }
            PhpObject object = requireInstanceObject(args, 0, "ReflectionProperty::isInitialized");
            if (state.dynamic()) {
                return DynamicObject.ContainsKeyNode.getUncached().execute(object, state.dynamicName());
            }
            PhpClass.InstanceProp prop = effectiveInstanceProp(state);
            if (prop != null && prop.virtual()) {
                return true;
            }
            Object value = rawInstanceProperty(ctx, object, state);
            return value != PhpClass.UNINITIALIZED;
        });
        addMethod(language, methods, "getType", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PhpType type = propertyState(self).decl() == null ? null : propertyState(self).decl().type();
            return type == null ? PhpNull.INSTANCE : typeObject(ctx, type);
        });
        addMethod(language, methods, "hasType", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> propertyState(self).decl() != null && propertyState(self).decl().type() != null);
        addMethod(language, methods, "hasDefaultValue", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PropertyState state = propertyState(self);
            if (state.dynamic()) {
                return false;
            }
            return propertyHasDefault(new PropertyInfo(state.reflected(), state.declaring(), state.decl()));
        });
        addMethod(language, methods, "getDefaultValue", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PropertyState state = propertyState(self);
            if (state.dynamic()) {
                return PhpNull.INSTANCE;
            }
            PropertyInfo info = new PropertyInfo(state.reflected(), state.declaring(), state.decl());
            if (!propertyHasDefault(info)) {
                return PhpNull.INSTANCE;
            }
            return state.declaring().evaluatePropertyDefault(state.decl());
        });
        addMethod(language, methods, "isPublic", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> propertyState(self).dynamic()
                        || propertyState(self).decl().visibility() == Visibility.PUBLIC);
        addMethod(language, methods, "isProtected", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> !propertyState(self).dynamic()
                        && propertyState(self).decl().visibility() == Visibility.PROTECTED);
        addMethod(language, methods, "isPrivate", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> !propertyState(self).dynamic()
                        && propertyState(self).decl().visibility() == Visibility.PRIVATE);
        addMethod(language, methods, "getDocComment", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> false);
        addMethod(language, methods, "isStatic", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> !propertyState(self).dynamic() && propertyState(self).decl().isStatic());
        addMethod(language, methods, "isReadOnly", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> !propertyState(self).dynamic() && propertyState(self).decl().isReadonly());
        addMethod(language, methods, "isPromoted", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PropertyState state = propertyState(self);
            if (state.dynamic()) {
                return false;
            }
            return isPromotedProperty(new PropertyInfo(state.reflected(), state.declaring(), state.decl()));
        });
        addMethod(language, methods, "isDefault", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> !propertyState(self).dynamic());
        addMethod(language, methods, "getModifiers", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PropertyState state = propertyState(self);
            return state.dynamic() ? 1L
                    : (long) propertyModifiers(state.decl(), effectiveInstanceProp(state));
        });
        addMethod(language, methods, "getDeclaringClass", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PropertyState state = propertyState(self);
            return reflectionClassObject(ctx, state.declaring() == null ? state.reflected() : state.declaring(), null);
        });
        addMethod(language, methods, "getAttributes", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PropertyState state = propertyState(self);
            return attributes(ctx, state.decl() == null ? List.of() : state.decl().attributes(), args,
                    TARGET_PROPERTY);
        });
        addMethod(language, methods, "setAccessible", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> PhpNull.INSTANCE);
        addMethod(language, methods, "__toString", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> propertyState(self).name());
        register(out, new PhpClassTemplate(language.nextTemplateId(), "ReflectionProperty", false, false, false,
                false, false, null, List.of("Stringable", "Reflector"), List.of(), nameClassProps(), List.of(), methods));

        addConstantsTo(out, "reflectionproperty", List.of(
                Map.entry("IS_STATIC", 16L), Map.entry("IS_READONLY", 128L), Map.entry("IS_PUBLIC", 1L),
                Map.entry("IS_PROTECTED", 2L), Map.entry("IS_PRIVATE", 4L), Map.entry("IS_ABSTRACT", 64L),
                Map.entry("IS_PROTECTED_SET", 2048L), Map.entry("IS_PRIVATE_SET", 4096L),
                Map.entry("IS_VIRTUAL", 512L), Map.entry("IS_FINAL", 32L)));
    }

    private static PropertyState propertyState(PhpObject self) {
        if (self != null && self.nativeState() instanceof PropertyState state) {
            return state;
        }
        throw new PhpError("ReflectionProperty object is not initialized");
    }

    private static String propertyStorageKey(PhpContext ctx, PhpClass declaring, String name) {
        PhpClass.PropertyResolution resolution = declaring.resolveProperty(ctx, name, declaring.lowerName());
        return resolution.storageKey();
    }

    private static Object getInstanceProperty(PhpContext ctx, PhpObject object, PropertyState state) {
        PhpClass.InstanceProp prop = effectiveInstanceProp(state);
        if (prop != null) {
            PhpClass.PropertyHook hook = prop.getHook();
            if (hook != null && !hook.isAbstract()) {
                Object value = PhpClass.callHook(object, prop, hook, null, dev.phpruntime.runtime.PhpObject.GUARD_HOOK_GET);
                return PhpClass.unwrapProp(value);
            }
            if (prop.virtual()) {
                throw new PhpError("Cannot read from set-only virtual property "
                        + state.declaring().name() + "::$" + state.name());
            }
        }
        Object value = rawInstanceProperty(ctx, object, state);
        if (value == PhpClass.UNINITIALIZED) {
            throw new PhpError("Typed property " + state.declaring().name() + "::$" + state.name()
                    + " must not be accessed before initialization");
        }
        return PhpClass.unwrapProp(value);
    }

    private static Object rawInstanceProperty(PhpContext ctx, PhpObject object, PropertyState state) {
        String key = propertyStorageKey(ctx, state.declaring(), state.name());
        Object value = DynamicObject.GetNode.getUncached().execute(object, key, PhpNull.INSTANCE);
        return value == null ? PhpNull.INSTANCE : value;
    }

    private static void setInstanceProperty(PhpContext ctx, PhpObject object, PropertyState state, Object value) {
        PhpClass.InstanceProp prop = effectiveInstanceProp(state);
        if (prop != null) {
            PhpClass.PropertyHook hook = prop.setHook();
            if (hook != null && !hook.isAbstract()) {
                PhpClass.callHook(object, prop, hook, value, dev.phpruntime.runtime.PhpObject.GUARD_HOOK_SET);
                return;
            }
            if (prop.virtual()) {
                throw new PhpError("Cannot write to get-only virtual property "
                        + state.declaring().name() + "::$" + state.name());
            }
        }
        String key = propertyStorageKey(ctx, state.declaring(), state.name());
        DynamicObject.PutNode.getUncached().execute(object, key, PhpOps.copyOnAssign(value));
    }

    /**
     * {@code setRawValue} writes the backing store without running a set hook but still
     * enforces the declared type and the readonly initialization rule. The
     * {@code WithoutLazyInitialization} variant additionally requires an instance of the
     * reflected class and rejects static and dynamic properties, as php does.
     */
    private static Object setRawPropertyValue(PhpContext ctx, PropertyState state, Object[] args,
                                              boolean withoutLazy) {
        String function = withoutLazy ? "ReflectionProperty::setRawValueWithoutLazyInitialization"
                : "ReflectionProperty::setRawValue";
        requireArgCount(function, args, 2, 2);
        Object objectArg = args[0];
        if (!withoutLazy && !(objectArg instanceof PhpObject)) {
            throw new PhpError(PhpError.TYPE_ERROR, function + "(): Argument #1 ($object) must be of type"
                    + " object, " + PhpOps.typeName(objectArg) + " given", null);
        }
        if (withoutLazy && (!(objectArg instanceof PhpObject objectArgObj)
                || !objectArgObj.phpClass().isInstanceOf(state.reflected()))) {
            throw new PhpError(PhpError.TYPE_ERROR, function + "(): Argument #1 ($object) must be of type "
                    + state.reflected().name() + ", " + valueTypeName(objectArg) + " given", null);
        }
        PhpObject object = (PhpObject) objectArg;
        if (state.dynamic()) {
            if (withoutLazy) {
                throw reflectionError("Cannot use setRawValueWithoutLazyInitialization() on dynamic property "
                        + state.reflected().name() + "::$" + state.dynamicName());
            }
            DynamicObject.PutNode.getUncached().execute(object, state.dynamicName(),
                    PhpOps.copyOnAssign(args[1]));
            return PhpNull.INSTANCE;
        }
        if (state.decl().isStatic()) {
            if (withoutLazy) {
                throw reflectionError("Cannot use setRawValueWithoutLazyInitialization() on static property "
                        + state.declaring().name() + "::$" + state.name());
            }
            throw reflectionError("May not use setRawValue on static properties");
        }
        PhpClass.InstanceProp prop = effectiveInstanceProp(state);
        if (prop != null && prop.virtual()) {
            if (prop.setHook() == null) {
                throw new PhpError("Cannot write to get-only virtual property "
                        + state.declaring().name() + "::$" + state.name());
            }
            throw new PhpError("Must not write to virtual property "
                    + state.declaring().name() + "::$" + state.name());
        }
        if (state.decl().writeBlocked()) {
            if (withoutLazy) {
                throw reflectionError("Cannot use setRawValueWithoutLazyInitialization() on internal class "
                        + state.declaring().name());
            }
            throw reflectionError("Cannot set read-only property " + object.phpClass().name()
                    + "::$" + state.name());
        }
        if (!withoutLazy && !object.phpClass().isInstanceOf(state.declaring())) {
            ctx.deprecated("Calling ReflectionProperty::setRawValue() with a given object that is not"
                    + " an instance of the class this property was declared in is deprecated", 0);
            DynamicObject.PutNode.getUncached().execute(object, state.name(), PhpOps.copyOnAssign(args[1]));
            return PhpNull.INSTANCE;
        }
        Object current = rawInstanceProperty(ctx, object, state);
        if (prop != null && prop.isReadonly() && current != PhpClass.UNINITIALIZED) {
            throw new PhpError("Cannot modify readonly property "
                    + state.declaring().name() + "::$" + state.name());
        }
        Object value = args[1];
        if (prop != null && prop.declaredType() != null) {
            value = prop.declaredType().checkProperty(value == null ? PhpNull.INSTANCE : value, ctx, null,
                    false, object.phpClass(), state.declaring().name(), state.name());
        }
        String key = propertyStorageKey(ctx, state.declaring(), state.name());
        DynamicObject.PutNode.getUncached().execute(object, key, PhpOps.copyOnAssign(value));
        return PhpNull.INSTANCE;
    }

    private static PhpClass.InstanceProp effectiveInstanceProp(PropertyState state) {
        if (state.decl() == null || state.decl().isStatic()) {
            return null;
        }
        PhpClass.InstanceProp prop = state.declaring().findVisibleProp(state.name());
        if (prop == null) {
            prop = state.declaring().findPrivateProp(state.name());
        }
        return prop;
    }

    private static Visibility effectiveSetVisibility(PropertyState state) {
        PhpClass.InstanceProp prop = effectiveInstanceProp(state);
        if (prop != null) {
            if (prop.setVisibility() != null) {
                return prop.setVisibility();
            }
            if (prop.isReadonly() && prop.visibility() == Visibility.PUBLIC) {
                return Visibility.PROTECTED;
            }
            return null;
        }
        PhpClassTemplate.PropertyDecl decl = state.decl();
        if (decl == null) {
            return null;
        }
        if (decl.setVisibility() != null) {
            return decl.setVisibility();
        }
        if (decl.isReadonly() && decl.visibility() == Visibility.PUBLIC) {
            return Visibility.PROTECTED;
        }
        return null;
    }

    private static PhpObject reflectionMethodObject(PhpContext ctx, PropertyState state,
                                                    PhpClass.InstanceProp prop, PhpClass.PropertyHook hook) {
        String hookName = "$" + prop.name() + "::" + hook.name();
        Visibility hookVisibility = prop.visibility() == Visibility.PRIVATE ? Visibility.PRIVATE : Visibility.PUBLIC;
        PhpClass.MethodEntry entry = new PhpClass.MethodEntry(hookName, hook.declaringClass(),
                hookVisibility, false, hook.isFinal(), hook.fromTrait(), hook.callTarget());
        PhpFunctionRootNode root = hook.callTarget() == null ? null
                : PhpFunctionRootNode.of(hook.callTarget().getRootNode());
        return newReflectionObject(ctx, "ReflectionMethod",
                new MethodState(state.reflected(), entry, hook.declaringClass(), hook.signature(), root, false));
    }

    private static Object getObjectProperty(PhpObject object, String name) {
        return PhpClass.unwrapProp(DynamicObject.GetNode.getUncached().execute(object, name, PhpNull.INSTANCE));
    }

    private static PhpObject requireInstanceObject(Object[] args, int index, String function) {
        if (index >= args.length || args[index] == PhpNull.INSTANCE || args[index] == null) {
            throw new PhpError(PhpError.TYPE_ERROR,
                    function + "(): Argument #1 ($object) must be provided for instance properties", null);
        }
        if (!(args[index] instanceof PhpObject object)) {
            throw new PhpError(PhpError.TYPE_ERROR,
                    function + "(): Argument #1 ($object) must be of type ?object, "
                            + PhpOps.typeName(args[index]) + " given", null);
        }
        return object;
    }

    // ---------------------------------------------------------------- ReflectionClassConstant

    /** The PHP 8.4 {@code PropertyHookType} enum used by {@code ReflectionProperty::getHook()}. */
    private static void buildPropertyHookType(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        register(out, new PhpClassTemplate(language.nextTemplateId(), "PropertyHookType", false, false,
                false, false, false, null, List.<String>of(),
                List.<PhpClassTemplate.TraitUse>of(), List.<PhpClassTemplate.PropertyDecl>of(),
                List.<PhpClassTemplate.ConstantDecl>of(), Map.<String, PhpClassTemplate.MethodDecl>of(),
                Map.<String, PhpClassTemplate.MethodSignature>of(), List.<PhpAttribute>of(), true, null,
                List.of(new PhpClassTemplate.EnumCaseDecl("Get", null, null, List.of()),
                        new PhpClassTemplate.EnumCaseDecl("Set", null, null, List.of()))));
    }

    private static void buildReflectionClassConstant(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        addMethod(language, methods, "__construct", Visibility.PUBLIC, false, false,
                List.of("class", "constant"), (ctx, self, args) -> {
            requireArgCount("ReflectionClassConstant::__construct", args, 2, 2);
            PhpClass cls = ctx.lookupOrAutoloadClass(PhpOps.toStr(args[0]));
            if (cls == null) {
                throw reflectionError("Class \"" + PhpOps.toStr(args[0]) + "\" does not exist");
            }
            String name = PhpOps.toStr(args[1]);
            ConstantInfo info = findConstant(cls, name);
            if (info == null) {
                throw reflectionError("Constant " + cls.name() + "::" + name + " does not exist");
            }
            boolean enumCase = info.declaring().template().isEnum()
                    && info.declaring().template().enumCase(info.decl().name()) != null;
            attach(self, new ConstantState(cls, info.declaring(), info.decl(), enumCase));
            return PhpNull.INSTANCE;
        });
        addMethod(language, methods, "getName", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> constantState(self).decl().name());
        addMethod(language, methods, "getValue", Visibility.PUBLIC, false, false, (ctx, self, args) ->
                constantState(self).declaring().lookupConstant(ctx, constantState(self).decl().name()));
        addMethod(language, methods, "getDeclaringClass", Visibility.PUBLIC, false, false, (ctx, self, args) ->
                reflectionClassObject(ctx, constantState(self).declaring(), null));
        addMethod(language, methods, "isPublic", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> constantState(self).decl().visibility() == Visibility.PUBLIC);
        addMethod(language, methods, "isProtected", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> constantState(self).decl().visibility() == Visibility.PROTECTED);
        addMethod(language, methods, "isPrivate", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> constantState(self).decl().visibility() == Visibility.PRIVATE);
        addMethod(language, methods, "isFinal", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> constantState(self).decl().isFinal());
        addMethod(language, methods, "isEnumCase", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> constantState(self).enumCase());
        addMethod(language, methods, "getModifiers", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> (long) constantModifiers(constantState(self).decl()));
        addMethod(language, methods, "getAttributes", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> attributes(ctx, constantState(self).decl().attributes(), args,
                        TARGET_CLASS_CONSTANT));
        addMethod(language, methods, "__toString", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> constantState(self).decl().name());
        register(out, new PhpClassTemplate(language.nextTemplateId(), "ReflectionClassConstant", false, false,
                false, false, false, null, List.of("Stringable", "Reflector"), List.of(), nameClassProps(), List.of(),
                methods));

        addConstantsTo(out, "reflectionclassconstant", List.of(
                Map.entry("IS_PUBLIC", 1L), Map.entry("IS_PROTECTED", 2L), Map.entry("IS_PRIVATE", 4L),
                Map.entry("IS_FINAL", 32L)));
    }

    private static ConstantState constantState(PhpObject self) {
        if (self != null && self.nativeState() instanceof ConstantState state) {
            return state;
        }
        throw new PhpError("ReflectionClassConstant object is not initialized");
    }

    // ---------------------------------------------------------------- ReflectionEnum

    private static void buildReflectionEnum(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        addMethod(language, methods, "__construct", Visibility.PUBLIC, false, false,
                List.of("objectOrClass"), (ctx, self, args) -> {
            requireArgCount("ReflectionEnum::__construct", args, 1, 1);
            PhpClass cls;
            if (args[0] instanceof PhpObject object) {
                cls = object.phpClass();
            } else {
                String name = PhpOps.toStr(args[0]);
                cls = ctx.lookupOrAutoloadClass(name);
                if (cls == null) {
                    throw reflectionError("Class \"" + name + "\" does not exist");
                }
            }
            if (!cls.template().isEnum()) {
                throw reflectionError("Class \"" + cls.name() + "\" is not an enum");
            }
            attach(self, new ClassState(cls, args[0] instanceof PhpObject object ? object : null));
            return PhpNull.INSTANCE;
        });
        addMethod(language, methods, "isBacked", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> classState(self).cls().template().enumBackingType() != null);
        addMethod(language, methods, "getBackingType", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            PhpType backing = classState(self).cls().template().enumBackingType();
            return backing == null ? PhpNull.INSTANCE
                    : newReflectionObject(ctx, "ReflectionNamedType", new TypeState(backing));
        });
        addMethod(language, methods, "hasCase", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            requireArgCount("ReflectionEnum::hasCase", args, 1, 1);
            String name = PhpOps.toStr(args[0]);
            return classState(self).cls().template().enumCase(name) != null;
        });
        addMethod(language, methods, "getCase", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            requireArgCount("ReflectionEnum::getCase", args, 1, 1);
            PhpClass cls = classState(self).cls();
            String name = PhpOps.toStr(args[0]);
            PhpClassTemplate.EnumCaseDecl decl = cls.template().enumCase(name);
            if (decl == null) {
                throw reflectionError("Case " + cls.name() + "::" + name + " does not exist");
            }
            return enumCaseObject(ctx, cls, decl);
        });
        addMethod(language, methods, "getCases", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            requireArgCount("ReflectionEnum::getCases", args, 0, 0);
            PhpClass cls = classState(self).cls();
            PhpArray result = new PhpArray();
            for (PhpClassTemplate.EnumCaseDecl decl : cls.template().enumCases()) {
                result.append(enumCaseObject(ctx, cls, decl));
            }
            return result;
        });
        register(out, new PhpClassTemplate(language.nextTemplateId(), "ReflectionEnum", false, false, false,
                false, false, "ReflectionClass", List.of(), List.of(), List.of(), List.of(), methods));
    }

    private static void buildReflectionEnumUnitCase(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        addMethod(language, methods, "__construct", Visibility.PUBLIC, false, false,
                List.of("class", "constant"), (ctx, self, args) -> {
            requireArgCount("ReflectionEnumUnitCase::__construct", args, 2, 2);
            PhpClass cls = ctx.lookupOrAutoloadClass(PhpOps.toStr(args[0]));
            if (cls == null) {
                throw reflectionError("Class \"" + PhpOps.toStr(args[0]) + "\" does not exist");
            }
            String name = PhpOps.toStr(args[1]);
            PhpClassTemplate.EnumCaseDecl decl = cls.template().isEnum() ? cls.template().enumCase(name) : null;
            if (decl == null) {
                throw reflectionError("Constant " + cls.name() + "::" + name + " does not exist");
            }
            attach(self, new EnumCaseRefState(cls, decl));
            return PhpNull.INSTANCE;
        });
        addMethod(language, methods, "getName", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> enumCaseState(self).decl().name());
        addMethod(language, methods, "getValue", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            EnumCaseRefState state = enumCaseState(self);
            Object value = state.enumClass().enumCaseValue(ctx, state.decl().name());
            return value == null ? PhpNull.INSTANCE : value;
        });
        addMethod(language, methods, "getDeclaringClass", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> reflectionClassObject(ctx, enumCaseState(self).enumClass(), null));
        addMethod(language, methods, "getEnum", Visibility.PUBLIC, false, false, (ctx, self, args) ->
                newReflectionObject(ctx, "ReflectionEnum",
                        new ClassState(enumCaseState(self).enumClass(), null)));
        addMethod(language, methods, "isPublic", Visibility.PUBLIC, false, false, (ctx, self, args) -> true);
        addMethod(language, methods, "isProtected", Visibility.PUBLIC, false, false, (ctx, self, args) -> false);
        addMethod(language, methods, "isPrivate", Visibility.PUBLIC, false, false, (ctx, self, args) -> false);
        addMethod(language, methods, "isFinal", Visibility.PUBLIC, false, false, (ctx, self, args) -> false);
        addMethod(language, methods, "isEnumCase", Visibility.PUBLIC, false, false, (ctx, self, args) -> true);
        addMethod(language, methods, "getModifiers", Visibility.PUBLIC, false, false, (ctx, self, args) -> 1L);
        addMethod(language, methods, "getAttributes", Visibility.PUBLIC, false, false, (ctx, self, args) ->
                attributes(ctx, enumCaseState(self).decl().attributes(), args, TARGET_CLASS_CONSTANT));
        addMethod(language, methods, "__toString", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            EnumCaseRefState state = enumCaseState(self);
            return "Constant [ public " + state.enumClass().name() + " " + state.decl().name()
                    + " ] { Object }\n";
        });
        register(out, new PhpClassTemplate(language.nextTemplateId(), "ReflectionEnumUnitCase", false, false,
                false, false, false, "ReflectionClassConstant", List.of(), List.of(), List.of(), List.of(),
                methods));
    }

    private static void buildReflectionEnumBackedCase(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        addMethod(language, methods, "__construct", Visibility.PUBLIC, false, false,
                List.of("class", "constant"), (ctx, self, args) -> {
            requireArgCount("ReflectionEnumBackedCase::__construct", args, 2, 2);
            PhpClass cls = ctx.lookupOrAutoloadClass(PhpOps.toStr(args[0]));
            if (cls == null) {
                throw reflectionError("Class \"" + PhpOps.toStr(args[0]) + "\" does not exist");
            }
            String name = PhpOps.toStr(args[1]);
            PhpClassTemplate.EnumCaseDecl decl = cls.template().isEnum() ? cls.template().enumCase(name) : null;
            if (decl == null) {
                throw reflectionError("Constant " + cls.name() + "::" + name + " does not exist");
            }
            if (cls.template().enumBackingType() == null) {
                throw reflectionError("Enum case " + cls.name() + "::" + name + " is not a backed case");
            }
            attach(self, new EnumCaseRefState(cls, decl));
            return PhpNull.INSTANCE;
        });
        addMethod(language, methods, "getBackingValue", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            EnumCaseRefState state = enumCaseState(self);
            Object value = state.enumClass().enumCaseValue(ctx, state.decl().name());
            if (value instanceof PhpObject obj && obj.nativeState() instanceof dev.phpruntime.runtime.EnumCaseState ec) {
                Object backing = ec.value();
                return backing == null ? PhpNull.INSTANCE : backing;
            }
            return PhpNull.INSTANCE;
        });
        register(out, new PhpClassTemplate(language.nextTemplateId(), "ReflectionEnumBackedCase", false, false,
                false, false, false, "ReflectionEnumUnitCase", List.of(), List.of(), List.of(), List.of(),
                methods));
    }

    private static EnumCaseRefState enumCaseState(PhpObject self) {
        if (self != null && self.nativeState() instanceof EnumCaseRefState state) {
            return state;
        }
        throw new PhpError("ReflectionEnumUnitCase object is not initialized");
    }

    private static PhpObject enumCaseObject(PhpContext ctx, PhpClass cls,
                                            PhpClassTemplate.EnumCaseDecl decl) {
        String reflectionClass = cls.template().enumBackingType() == null
                ? "ReflectionEnumUnitCase" : "ReflectionEnumBackedCase";
        return newReflectionObject(ctx, reflectionClass, new EnumCaseRefState(cls, decl));
    }

    // ---------------------------------------------------------------- ReflectionAttribute

    private static final int TARGET_CLASS = 1;
    private static final int TARGET_FUNCTION = 2;
    private static final int TARGET_METHOD = 4;
    private static final int TARGET_PROPERTY = 8;
    private static final int TARGET_CLASS_CONSTANT = 16;
    private static final int TARGET_PARAMETER = 32;
    private static final int IS_INSTANCEOF = 2;

    private static void buildReflectionAttribute(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        addMethod(language, methods, "getName", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> attributeState(self).attribute().name());
        addMethod(language, methods, "getArguments", Visibility.PUBLIC, false, false, (ctx, self, args) ->
                attributeArguments(ctx, attributeState(self)));
        addMethod(language, methods, "getTarget", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> (long) attributeState(self).target());
        addMethod(language, methods, "isRepeated", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> attributeState(self).repeated());
        addMethod(language, methods, "newInstance", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            AttributeState state = attributeState(self);
            PhpAttribute attribute = state.attribute();
            PhpClass cls = ctx.lookupOrAutoloadClass(attribute.name());
            if (cls == null) {
                throw new PhpError("Attribute class \"" + attribute.name() + "\" not found");
            }
            boolean isAttribute = hasAttributeMarker(cls);
            if (!isAttribute) {
                throw new PhpError("Attempting to use non-attribute class \"" + attribute.name() + "\" as attribute");
            }
            long flags = attributeFlags(ctx, cls);
            if ((flags & state.target()) == 0) {
                throw new PhpError("Attribute \"" + attribute.name() + "\" cannot target "
                        + targetName(state.target()) + " (allowed targets: " + allowedTargets(flags) + ")");
            }
            if (state.repeated() && (flags & 128L) == 0) {
                throw new PhpError("Attribute \"" + attribute.name() + "\" must not be repeated");
            }
            PhpArray values = attributeArguments(ctx, state);
            return instantiate(ctx, cls, values, false, null);
        });
        addMethod(language, methods, "__toString", Visibility.PUBLIC, false, false,
                (ctx, self, args) -> attributeState(self).attribute().name());
        register(out, new PhpClassTemplate(language.nextTemplateId(), "ReflectionAttribute", false, false, false,
                false, false, null, List.of("Stringable", "Reflector"), List.of(), List.of(), List.of(), methods));

        addConstantsTo(out, "reflectionattribute", List.of(
                Map.entry("IS_INSTANCEOF", 2L),
                Map.entry("TARGET_CLASS", 1L), Map.entry("TARGET_FUNCTION", 2L),
                Map.entry("TARGET_METHOD", 4L), Map.entry("TARGET_PROPERTY", 8L),
                Map.entry("TARGET_CLASS_CONSTANT", 16L), Map.entry("TARGET_PARAMETER", 32L),
                Map.entry("TARGET_ALL", 127L)));
    }

    private static AttributeState attributeState(PhpObject self) {
        if (self != null && self.nativeState() instanceof AttributeState state) {
            return state;
        }
        throw new PhpError("ReflectionAttribute object is not initialized");
    }

    private static boolean hasAttributeMarker(PhpClass cls) {
        for (PhpAttribute attribute : cls.template().attributes()) {
            if (PhpStrings.equalsIgnoreCase(attribute.name(), "Attribute")
                    || PhpStrings.equalsIgnoreCase(attribute.name(), "\\Attribute")) {
                return true;
            }
        }
        return false;
    }

    private static long attributeFlags(PhpContext ctx, PhpClass cls) {
        for (PhpAttribute attribute : cls.template().attributes()) {
            if (PhpStrings.equalsIgnoreCase(attribute.name(), "Attribute")
                    || PhpStrings.equalsIgnoreCase(attribute.name(), "\\Attribute")) {
                if (attribute.arguments().isEmpty()) {
                    return 127L;
                }
                for (PhpAttribute.Argument argument : attribute.arguments()) {
                    Object value = argument.value();
                    if (value == null && argument.target() != null) {
                        value = evalTarget(argument.target(), cls);
                    }
                    if (value instanceof Long flags) {
                        return flags;
                    }
                }
                return 0L;
            }
        }
        return 0L;
    }

    private static String targetName(int target) {
        return switch (target) {
            case TARGET_CLASS -> "class";
            case TARGET_FUNCTION -> "function";
            case TARGET_METHOD -> "method";
            case TARGET_PROPERTY -> "property";
            case TARGET_CLASS_CONSTANT -> "class constant";
            case TARGET_PARAMETER -> "parameter";
            default -> "";
        };
    }

    private static String allowedTargets(long flags) {
        List<String> names = new ArrayList<>();
        if ((flags & TARGET_CLASS) != 0) {
            names.add("class");
        }
        if ((flags & TARGET_FUNCTION) != 0) {
            names.add("function");
        }
        if ((flags & TARGET_METHOD) != 0) {
            names.add("method");
        }
        if ((flags & TARGET_PROPERTY) != 0) {
            names.add("property");
        }
        if ((flags & TARGET_CLASS_CONSTANT) != 0) {
            names.add("class constant");
        }
        if ((flags & TARGET_PARAMETER) != 0) {
            names.add("parameter");
        }
        return String.join(", ", names);
    }

    private static PhpArray attributeArguments(PhpContext ctx, AttributeState state) {
        PhpArray out = new PhpArray();
        for (PhpAttribute.Argument argument : state.attribute().arguments()) {
            Object value;
            if (argument.value() != null) {
                value = argument.value();
            } else if (argument.target() != null) {
                value = evalTarget(argument.target(), null);
            } else {
                value = PhpNull.INSTANCE;
            }
            Object copied = PhpOps.copyOnAssign(value == null ? PhpNull.INSTANCE : value);
            if (argument.name() != null) {
                out.put(argument.name(), copied);
            } else {
                out.append(copied);
            }
        }
        return out;
    }

    private static PhpArray attributes(PhpContext ctx, List<PhpAttribute> attributes, Object[] args, int target) {
        String filter = args.length > 0 && args[0] != PhpNull.INSTANCE ? PhpOps.toStr(args[0]) : null;
        boolean instanceOf = args.length > 1 && args[1] != PhpNull.INSTANCE
                && (toLong(args[1]) & IS_INSTANCEOF) != 0;
        PhpClass filterClass = null;
        if (filter != null && instanceOf) {
            filterClass = ctx.lookupOrAutoloadClass(filter);
            if (filterClass == null) {
                throw new PhpError("Class \"" + filter + "\" not found");
            }
        }
        PhpArray out = new PhpArray();
        for (PhpAttribute attribute : attributes) {
            if (filter != null && !matchesAttribute(ctx, attribute.name(), filter, instanceOf, filterClass)) {
                continue;
            }
            int count = 0;
            for (PhpAttribute other : attributes) {
                if (PhpStrings.equalsIgnoreCase(other.name(), attribute.name())) {
                    count++;
                }
            }
            out.append(newReflectionObject(ctx, "ReflectionAttribute",
                    new AttributeState(attribute, target, count > 1)));
        }
        return out;
    }

    private static boolean matchesAttribute(PhpContext ctx, String name, String filter, boolean instanceOf,
                                            PhpClass filterClass) {
        if (!instanceOf) {
            return PhpStrings.equalsIgnoreCase(name, filter);
        }
        PhpClass actual = ctx.lookupOrAutoloadClass(name);
        return filterClass != null && actual != null && actual.isInstanceOf(filterClass);
    }

    private record ReferenceState(PhpReference reference, String id)
            implements PhpNativeState, SnapshotNativeState {
        @Override
        public PhpNativeState copy() {
            return this;
        }

        @Override
        public Object snapshotNativeState(Function<Object, Object> map) {
            return new ReferenceStateSnapshot(map.apply(reference));
        }

        private record ReferenceStateSnapshot(Object reference) implements RestoredNativeState {
            @Override
            public PhpNativeState restoreNativeState(PhpContext ctx, Function<Object, Object> unmap) {
                PhpReference restored = (PhpReference) unmap.apply(reference);
                // getId() is only unique within one request: it must name the restored cell, not
                // keep the identity hash the template run happened to see.
                return new ReferenceState(restored,
                        String.format("%020x", System.identityHashCode(restored)));
            }
        }
    }

    private static void buildReflectionReference(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        addMethod(language, methods, "__construct", Visibility.PRIVATE, false, false, (ctx, self, args) -> {
            throw new PhpError("Call to private ReflectionReference::__construct() from global scope");
        });
        addMethod(language, methods, "fromArrayElement", Visibility.PUBLIC, true, false, (ctx, self, args) -> {
            requireArgCount("ReflectionReference::fromArrayElement", args, 2, 2);
            if (!(args[0] instanceof PhpArray array)) {
                throw new PhpError(PhpError.TYPE_ERROR, "ReflectionReference::fromArrayElement(): Argument #1 ($array)"
                        + " must be of type array, " + PhpOps.typeDescription(args[0]) + " given", null);
            }
            Object key = args[1];
            if (!(key instanceof String || key instanceof Long)) {
                throw new PhpError(PhpError.TYPE_ERROR, "ReflectionReference::fromArrayElement(): Argument #2 ($key)"
                        + " must be of type string|int, " + PhpOps.typeDescription(key) + " given", null);
            }
            if (!array.containsKey(key)) {
                throw reflectionError("Array key not found");
            }
            if (array.getRaw(key) instanceof PhpReference reference) {
                String id = String.format("%020x", System.identityHashCode(reference));
                return newReflectionObject(ctx, "ReflectionReference", new ReferenceState(reference, id));
            }
            return PhpNull.INSTANCE;
        });
        addMethod(language, methods, "getId", Visibility.PUBLIC, false, false, (ctx, self, args) -> {
            if (self.nativeState() instanceof ReferenceState state) {
                return state.id();
            }
            throw new PhpError("ReflectionReference object is not initialized");
        });
        register(out, new PhpClassTemplate(language.nextTemplateId(), "ReflectionReference", false, false,
                true, false, null, List.of(), List.of(), List.of(), methods));
    }

    // ---------------------------------------------------------------- instantiation and invocation

    private static Object instantiate(PhpContext ctx, PhpClass cls, PhpArray arguments, boolean withoutConstructor,
                                      String attributeName) {
        if (cls.isInterface()) {
            throw new PhpError("Cannot instantiate interface " + cls.name());
        }
        if (cls.isTrait()) {
            throw new PhpError("Cannot instantiate trait " + cls.name());
        }
        if (cls.template().isEnum()) {
            throw new PhpError("Cannot instantiate enum " + cls.name());
        }
        if (cls.isAbstract()) {
            throw new PhpError("Cannot instantiate abstract class " + cls.name());
        }
        if ("Closure".equalsIgnoreCase(cls.name())) {
            throw new PhpError("Instantiation of class Closure is not allowed");
        }
        PhpClass.MethodEntry ctor = cls.constructMethod();
        if (!withoutConstructor && ctor != null && ctor.visibility() != Visibility.PUBLIC) {
            throw reflectionError("Access to non-public constructor of class " + cls.name());
        }
        if (!withoutConstructor && ctor == null && arguments.size() > 0) {
            throw reflectionError("Class " + cls.name()
                    + " does not have a constructor, so you cannot pass any constructor arguments");
        }
        PhpObject object = new PhpObject(cls, ctx.nextObjectId());
        cls.populateDefaults(object);
        if (cls.isThrowable()) {
            PhpExceptions.initNewThrowable(object, null);
        }
        if (!withoutConstructor && ctor != null) {
            invokeConstructor(ctx, object, ctor, arguments);
        }
        return object;
    }

    private static void invokeConstructor(PhpContext ctx, PhpObject object, PhpClass.MethodEntry ctor, PhpArray arguments) {
        PhpFunctionRootNode root = ctor.callTarget() == null ? null : PhpFunctionRootNode.of(ctor.callTarget().getRootNode());
        PhpClass calledClass = object.phpClass();
        Object[] prefix = ctor.fromTrait()
                ? new Object[]{object, calledClass, ctor.declaringClass()}
                : new Object[]{object, calledClass};
        if (arguments.size() == 0) {
            ctor.callTarget().call(prefix);
            return;
        }
        List<Object> positional = new ArrayList<>();
        Map<String, Object> named = new LinkedHashMap<>();
        splitArguments(arguments, positional, named);
        if (named.isEmpty()) {
            Object[] callArgs = new Object[prefix.length + positional.size()];
            System.arraycopy(prefix, 0, callArgs, 0, prefix.length);
            for (int i = 0; i < positional.size(); i++) {
                callArgs[prefix.length + i] = positional.get(i);
            }
            ctor.callTarget().call(callArgs);
            return;
        }
        if (root == null) {
            throw new PhpError("Unknown named parameter $" + named.keySet().iterator().next());
        }
        CallArgHelper.RawCallArgs raw = rawCallArgs(positional, named);
        Object[] callArgs = CallArgHelper.bindToFunction(prefix, raw, root, null);
        ctor.callTarget().call(callArgs);
    }

    private static Object invokeMethod(PhpContext ctx, MethodState state, Object objectValue) {
        return invokeMethod(ctx, state, objectValue, List.of(), new LinkedHashMap<>());
    }

    private static Object invokeMethod(PhpContext ctx, MethodState state, Object objectValue,
                                       PhpArray values) {
        List<Object> positional = new ArrayList<>();
        Map<String, Object> named = new LinkedHashMap<>();
        splitArguments(values, positional, named);
        return invokeMethod(ctx, state, objectValue, positional, named);
    }

    private static Object invokeMethod(PhpContext ctx, MethodState state, Object objectValue,
                                       List<Object> positional, Map<String, Object> named) {
        PhpClass.MethodEntry entry = state.entry();
        PhpObject object = null;
        if (objectValue instanceof PhpObject candidate) {
            object = candidate;
        } else if (objectValue != PhpNull.INSTANCE && objectValue != null) {
            throw new PhpError(PhpError.TYPE_ERROR,
                    "ReflectionMethod::invoke(): Argument #1 ($object) must be of type ?object, "
                            + PhpOps.typeName(objectValue) + " given", null);
        }
        if (!entry.isStatic()) {
            if (object == null) {
                throw reflectionError("Trying to invoke non static method " + entry.declaringClass().name()
                        + "::" + entry.name() + "() without an object");
            }
            if (!object.phpClass().isInstanceOf(entry.declaringClass())) {
                throw reflectionError("Given object is not an instance of the class this method was declared in");
            }
        }
        if (entry.callTarget() == null) {
            throw reflectionError("Trying to invoke abstract method " + entry.declaringClass().name()
                    + "::" + entry.name() + "()");
        }
        Object thisArg = entry.isStatic() ? PhpNull.INSTANCE : object;
        PhpClass calledClass = entry.isStatic() ? state.reflected() : object.phpClass();
        Object[] prefix = entry.fromTrait()
                ? new Object[]{thisArg, calledClass, entry.declaringClass()}
                : new Object[]{thisArg, calledClass};
        if (named.isEmpty()) {
            Object[] callArgs = new Object[prefix.length + positional.size()];
            System.arraycopy(prefix, 0, callArgs, 0, prefix.length);
            for (int i = 0; i < positional.size(); i++) {
                callArgs[prefix.length + i] = positional.get(i);
            }
            return CallArgHelper.unwrapReturn(entry.callTarget().call(callArgs));
        }
        PhpFunctionRootNode root = PhpFunctionRootNode.of(entry.callTarget().getRootNode());
        CallArgHelper.RawCallArgs raw = rawCallArgs(positional, named);
        if (root == null) {
            return CallArgHelper.unwrapReturn(entry.callTarget().call(
                    CallArgHelper.bindToInternal(prefix, raw, entry, null)));
        }
        Object[] callArgs = CallArgHelper.bindToFunction(prefix, raw, root, null);
        return CallArgHelper.unwrapReturn(entry.callTarget().call(callArgs));
    }

    private static Object invokeCallable(PhpContext ctx, FunctionState state, Object[] positional,
                                         Map<String, Object> named, String description) {
        List<Object> list = new ArrayList<>(positional.length);
        for (Object value : positional) {
            list.add(value);
        }
        return invokeCallable(ctx, state, list, named);
    }

    private static Object invokeCallable(PhpContext ctx, FunctionState state, PhpArray values, String description) {
        List<Object> positional = new ArrayList<>();
        Map<String, Object> named = new LinkedHashMap<>();
        splitArguments(values, positional, named);
        return invokeCallable(ctx, state, positional, named);
    }

    private static Object invokeCallable(PhpContext ctx, FunctionState state, List<Object> positional,
                                         Map<String, Object> named) {
        if (named.isEmpty()) {
            return state.callable.call(ctx, null, null, positional.toArray());
        }
        if (state.callable.kind() == PhpCallable.Kind.BUILTIN) {
            Object[] bound = bindBuiltin(state.callable.builtin(), positional, named);
            return state.callable.builtin().call(ctx, bound);
        }
        CallArgHelper.RawCallArgs raw = rawCallArgs(positional, named);
        switch (state.callable.kind()) {
            case CLOSURE -> {
                Object[] prefix = new Object[]{state.callable.boundThis(), state.callable.calledClass(),
                        state.callable.closureObj()};
                return CallArgHelper.unwrapReturn(state.callable.callTarget().call(
                        CallArgHelper.bindToFunction(prefix, raw, state.root, null)));
            }
            case USER_FUNCTION -> {
                return CallArgHelper.unwrapReturn(state.callable.callTarget().call(
                        CallArgHelper.bindToFunction(new Object[0], raw, state.root, null)));
            }
            case METHOD -> {
                Object[] prefix = state.callable.traitSelf() != null
                        ? new Object[]{state.callable.boundThis(), state.callable.calledClass(),
                                state.callable.traitSelf()}
                        : new Object[]{state.callable.boundThis(), state.callable.calledClass()};
                if (state.root == null) {
                    return CallArgHelper.unwrapReturn(state.callable.callTarget().call(
                            CallArgHelper.bindToInternal(prefix, raw, state.callable.methodEntry(), null)));
                }
                return CallArgHelper.unwrapReturn(state.callable.callTarget().call(
                        CallArgHelper.bindToFunction(prefix, raw, state.root, null)));
            }
            case MAGIC_CALL -> {
                throw new PhpError("Cannot reflect a magic call");
            }
            default -> throw new PhpError("Cannot invoke this callable");
        }
    }

    private static Object[] bindBuiltin(Builtins.Def def, List<Object> positional, Map<String, Object> named) {
        BuiltinParams.Param[] params = BuiltinParams.forName(def.name());
        if (params == null) {
            throw new PhpError("Unknown named parameter $" + named.keySet().iterator().next());
        }
        int normal = params.length;
        while (normal > 0 && params[normal - 1].variadic()) {
            normal--;
        }
        if (positional.size() > normal) {
            throw new PhpError(PhpError.ARGUMENT_COUNT, def.name() + "() expects at most " + normal
                    + " arguments, " + positional.size() + " given", null);
        }
        int highest = positional.size() - 1;
        Map<Integer, String> namedAt = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : named.entrySet()) {
            int index = -1;
            for (int i = 0; i < normal; i++) {
                if (params[i].name().equals(entry.getKey())) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                throw new PhpError("Unknown named parameter $" + entry.getKey());
            }
            if (index < positional.size()) {
                throw new PhpError("Named parameter $" + entry.getKey() + " overwrites previous argument");
            }
            namedAt.put(index, entry.getKey());
            highest = Math.max(highest, index);
        }
        Object[] values = new Object[Math.max(positional.size(), highest + 1)];
        for (int i = 0; i < positional.size(); i++) {
            values[i] = positional.get(i);
        }
        for (int i = positional.size(); i < values.length; i++) {
            String name = namedAt.get(i);
            if (name != null) {
                values[i] = named.get(name);
                continue;
            }
            BuiltinParams.Param param = params[i];
            if (!param.hasDefault()) {
                throw new PhpError(PhpError.ARGUMENT_COUNT, def.name() + "(): Argument #" + (i + 1)
                        + " ($" + param.name() + ") not passed", null);
            }
            values[i] = param.defaultValue();
        }
        return values;
    }

    private static CallArgHelper.RawCallArgs rawCallArgs(List<Object> positional, Map<String, Object> named) {
        CallArgHelper.RawCallArgs raw = CallArgHelper.newRawCallArgs();
        raw.positional().addAll(positional);
        for (int i = 0; i < positional.size(); i++) {
            raw.posRefTargets().add(null);
            raw.posRefKeys().add(null);
            raw.posNodes().add(null);
        }
        for (Map.Entry<String, Object> entry : named.entrySet()) {
            raw.named().put(entry.getKey(), entry.getValue());
        }
        return raw;
    }

    private static void splitArguments(PhpArray values, List<Object> positional, Map<String, Object> named) {
        boolean seenNamed = false;
        for (Object key : values.keys()) {
            Object raw = values.getRaw(key);
            Object value = raw instanceof dev.phpruntime.runtime.PhpReference ref
                    ? ref : PhpOps.copyOnAssign(values.get(key));
            if (key instanceof String name) {
                seenNamed = true;
                named.put(name, value);
            } else {
                if (seenNamed) {
                    throw new PhpError("Cannot use positional argument after named argument");
                }
                positional.add(value);
            }
        }
    }

    // ---------------------------------------------------------------- small helpers

    private static PhpArray positionalArray(Object[] args) {
        PhpArray out = new PhpArray();
        for (Object arg : args) {
            out.append(arg);
        }
        return out;
    }

    private static List<Object> positionalArgs(Object[] args) {
        List<Object> out = new ArrayList<>(args.length);
        for (Object arg : args) {
            out.add(arg);
        }
        return out;
    }

    private static long toLong(Object value) {
        return PhpOps.toLong(value);
    }

    private static void requireArgCount(String function, Object[] args, int min, int max) {
        if (args.length < min || (max >= 0 && args.length > max)) {
            String expectation = min == max ? "exactly " + min : "at least " + min;
            throw new PhpError(PhpError.ARGUMENT_COUNT, function + "() expects " + expectation
                    + " argument" + (min == 1 ? "" : "s") + ", " + args.length + " given", null);
        }
    }

    private static PhpError reflectionError(String message) {
        return new PhpError("ReflectionException", message, null);
    }

    private static String shortName(String name) {
        int slash = name.lastIndexOf('\\');
        return slash < 0 ? name : name.substring(slash + 1);
    }

    private static String namespaceName(String name) {
        int slash = name.lastIndexOf('\\');
        return slash < 0 ? "" : name.substring(0, slash);
    }

    /** The name php uses for a value in an argument type error: an object shows its class. */
    private static String valueTypeName(Object value) {
        return value instanceof PhpObject object ? object.phpClass().name() : PhpOps.typeName(value);
    }

    private static List<PhpClass> collectInterfaces(PhpClass cls) {
        List<PhpClass> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        collectInterfaces(cls, out, seen);
        return out;
    }

    private static void collectInterfaces(PhpClass cls, List<PhpClass> out, Set<String> seen) {
        if (cls.parent() != null) {
            collectInterfaces(cls.parent(), out, seen);
        }
        for (PhpClass iface : cls.interfaces()) {
            addInterface(iface, out, seen);
        }
    }

    private static void addInterface(PhpClass iface, List<PhpClass> out, Set<String> seen) {
        if (!seen.add(iface.lowerName())) {
            return;
        }
        out.add(iface);
        for (PhpClass parent : iface.interfaces()) {
            addInterface(parent, out, seen);
        }
    }

    private static void addMethod(PhpLanguage language, Map<String, PhpClassTemplate.MethodDecl> methods,
                                  String name, Visibility visibility, boolean isStatic, boolean isFinal,
                                  PhpExceptions.BuiltinMethod impl) {
        addMethod(language, methods, name, visibility, isStatic, isFinal, List.of(), impl);
    }

    /** An internal method with parameter names, so named arguments bind as php's arginfo does. */
    private static void addMethod(PhpLanguage language, Map<String, PhpClassTemplate.MethodDecl> methods,
                                  String name, Visibility visibility, boolean isStatic, boolean isFinal,
                                  List<String> parameterNames, PhpExceptions.BuiltinMethod impl) {
        String lower = PhpStrings.lower(name);
        PhpClassTemplate.MethodDecl decl = new PhpClassTemplate.MethodDecl(name, lower, language.methodId(name),
                visibility, isStatic, false, isFinal,
                new PhpExceptions.BuiltinMethodRootNode(language, name, impl).getCallTarget());
        if (!parameterNames.isEmpty()) {
            List<PhpParameter> parameters = new ArrayList<>();
            for (String parameterName : parameterNames) {
                parameters.add(new PhpParameter(parameterName, null, List.of(), false, false, false, null, null));
            }
            decl = new PhpClassTemplate.MethodDecl(name, lower, language.methodId(name), visibility, isStatic,
                    false, isFinal, decl.callTarget(), new PhpSignature(parameters, null, List.of()));
        }
        methods.put(lower, decl);
    }

    private static void addAbstractMethod(PhpLanguage language, Map<String, PhpClassTemplate.MethodDecl> methods,
                                          String name) {
        String lower = PhpStrings.lower(name);
        methods.put(lower, new PhpClassTemplate.MethodDecl(name, lower, language.methodId(name), Visibility.PUBLIC,
                false, true, false, null));
    }

    private static void constant(List<PhpClassTemplate.ConstantDecl> constants, String name, Object value,
                                 Visibility visibility, boolean isFinal) {
        constants.add(new PhpClassTemplate.ConstantDecl(name, visibility, isFinal, value, null, null, List.of()));
    }
}
