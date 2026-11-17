package dev.phpruntime.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.object.Shape;

import java.util.List;
import java.util.Map;

/**
 * Shared, immutable description of a parsed PHP class, interface, or trait.
 * Lives on the AST / engine across requests; the linked form is {@link PhpClass}.
 */
public final class PhpClassTemplate {

    /**
     * The class most recently linked from this template and what it was linked against.
     * A request whose parent, interfaces and traits are these same classes reuses it.
     */
    public record Linked(PhpClass cls, PhpClass parent, PhpClass[] interfaces, PhpClass[] traits) {
        public boolean matches(PhpClass p, List<PhpClass> ifaces, List<PhpClass> trs) {
            if (parent != p || interfaces.length != ifaces.size() || traits.length != trs.size()) {
                return false;
            }
            for (int i = 0; i < interfaces.length; i++) {
                if (interfaces[i] != ifaces.get(i)) {
                    return false;
                }
            }
            for (int i = 0; i < traits.length; i++) {
                if (traits[i] != trs.get(i)) {
                    return false;
                }
            }
            return true;
        }
    }

    private volatile Linked linked;

    public Linked linked() {
        return linked;
    }

    public void setLinked(Linked l) {
        linked = l;
    }

    public record PropertyDecl(String name, Visibility visibility, boolean isStatic, boolean isReadonly,
                               Object constDefault, RootCallTarget defaultTarget) {
        public PropertyDecl(String name, Visibility visibility, boolean isStatic,
                            Object constDefault, RootCallTarget defaultTarget) {
            this(name, visibility, isStatic, false, constDefault, defaultTarget);
        }
    }

    public record ConstantDecl(String name, Visibility visibility, boolean isFinal,
                               Object constValue, RootCallTarget valueTarget) {
    }

    public record MethodDecl(String name, String lowerName, int methodId, Visibility visibility,
                             boolean isStatic, boolean isAbstract, boolean isFinal,
                             RootCallTarget callTarget) {
    }

    /** Internal interface signature metadata; tentative PHP 8 return types are not enforced. */
    public record MethodSignature(List<String> parameterNames, List<String> parameterTypes,
                                  String returnType, boolean tentativeReturn) {
        public MethodSignature {
            parameterNames = List.copyOf(parameterNames);
            parameterTypes = List.copyOf(parameterTypes);
        }
    }

    public record TraitPrecedence(String chosenTrait, String methodName, List<String> excludedTraits) {
    }

    public record TraitAlias(String traitName, String methodName, String aliasName, Visibility visibility) {
    }

    public record TraitUse(List<String> traitNames, List<TraitPrecedence> precedences, List<TraitAlias> aliases) {
    }

    private final long id;
    private final String name;
    private final String lowerName;
    private final boolean isInterface;
    private final boolean isTrait;
    private final boolean isAbstract;
    private final boolean isFinal;
    private final boolean isAnonymous;
    private final String parentName;
    private final List<String> interfaceNames;
    private final List<TraitUse> traitUses;
    private final List<PropertyDecl> properties;
    private final List<ConstantDecl> constants;
    private final Map<String, MethodDecl> methods;
    private final Map<String, MethodSignature> methodSignatures;
    private final Shape rootShape;

    public PhpClassTemplate(long id, String name, boolean isInterface, boolean isAbstract, boolean isFinal,
                            boolean isAnonymous, String parentName, List<String> interfaceNames,
                            List<PropertyDecl> properties, List<ConstantDecl> constants,
                            Map<String, MethodDecl> methods) {
        this(id, name, isInterface, false, isAbstract, isFinal, isAnonymous,
                parentName, interfaceNames, List.of(), properties, constants, methods);
    }

    public PhpClassTemplate(long id, String name, boolean isInterface, boolean isTrait,
                            boolean isAbstract, boolean isFinal, boolean isAnonymous,
                            String parentName, List<String> interfaceNames, List<TraitUse> traitUses,
                            List<PropertyDecl> properties, List<ConstantDecl> constants,
                            Map<String, MethodDecl> methods) {
        this(id, name, isInterface, isTrait, isAbstract, isFinal, isAnonymous, parentName, interfaceNames,
                traitUses, properties, constants, methods, Map.of());
    }

    public PhpClassTemplate(long id, String name, boolean isInterface, boolean isTrait,
                            boolean isAbstract, boolean isFinal, boolean isAnonymous,
                            String parentName, List<String> interfaceNames, List<TraitUse> traitUses,
                            List<PropertyDecl> properties, List<ConstantDecl> constants,
                            Map<String, MethodDecl> methods, Map<String, MethodSignature> methodSignatures) {
        this.id = id;
        this.name = name;
        this.lowerName = PhpStrings.lower(name);
        this.isInterface = isInterface;
        this.isTrait = isTrait;
        this.isAbstract = isAbstract;
        this.isFinal = isFinal;
        this.isAnonymous = isAnonymous;
        this.parentName = parentName;
        this.interfaceNames = List.copyOf(interfaceNames);
        this.traitUses = List.copyOf(traitUses);
        this.properties = List.copyOf(properties);
        this.constants = List.copyOf(constants);
        // Declaration order matters for get_class_methods.
        this.methods = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(methods));
        this.methodSignatures = Map.copyOf(methodSignatures);
        this.rootShape = Shape.newBuilder()
                .layout(PhpObject.class, PhpObject.LOOKUP)
                .dynamicType(this)
                .build();
    }

    public long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String lowerName() {
        return lowerName;
    }

    public boolean isInterface() {
        return isInterface;
    }

    public boolean isTrait() {
        return isTrait;
    }

    public boolean isAbstract() {
        return isAbstract;
    }

    public boolean isFinal() {
        return isFinal;
    }

    public boolean isAnonymous() {
        return isAnonymous;
    }

    public String parentName() {
        return parentName;
    }

    public List<String> interfaceNames() {
        return interfaceNames;
    }

    public List<TraitUse> traitUses() {
        return traitUses;
    }

    public List<PropertyDecl> properties() {
        return properties;
    }

    public List<ConstantDecl> constants() {
        return constants;
    }

    public Map<String, MethodDecl> methods() {
        return methods;
    }

    public Map<String, MethodSignature> methodSignatures() {
        return methodSignatures;
    }

    public Shape rootShape() {
        return rootShape;
    }
}
