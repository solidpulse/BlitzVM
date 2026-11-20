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
                               Object constDefault, RootCallTarget defaultTarget,
                               PhpType type, List<PhpAttribute> attributes, boolean hasDefault) {
        public PropertyDecl {
            attributes = attributes == null ? List.of() : List.copyOf(attributes);
        }

        public PropertyDecl(String name, Visibility visibility, boolean isStatic, boolean isReadonly,
                            Object constDefault, RootCallTarget defaultTarget, PhpType type,
                            List<PhpAttribute> attributes) {
            this(name, visibility, isStatic, isReadonly, constDefault, defaultTarget, type, attributes,
                    type == null || constDefault != PhpNull.INSTANCE || defaultTarget != null);
        }

        public PropertyDecl(String name, Visibility visibility, boolean isStatic, boolean isReadonly,
                            Object constDefault, RootCallTarget defaultTarget) {
            this(name, visibility, isStatic, isReadonly, constDefault, defaultTarget, null, List.of());
        }

        public PropertyDecl(String name, Visibility visibility, boolean isStatic,
                            Object constDefault, RootCallTarget defaultTarget) {
            this(name, visibility, isStatic, false, constDefault, defaultTarget, null, List.of());
        }
    }

    public record ConstantDecl(String name, Visibility visibility, boolean isFinal,
                               Object constValue, RootCallTarget valueTarget,
                               PhpType type, List<PhpAttribute> attributes) {
        public ConstantDecl {
            attributes = attributes == null ? List.of() : List.copyOf(attributes);
        }

        public ConstantDecl(String name, Visibility visibility, boolean isFinal,
                            Object constValue, RootCallTarget valueTarget) {
            this(name, visibility, isFinal, constValue, valueTarget, null, List.of());
        }
    }

    public record MethodDecl(String name, String lowerName, int methodId, Visibility visibility,
                             boolean isStatic, boolean isAbstract, boolean isFinal,
                             RootCallTarget callTarget, PhpSignature signature) {
        public MethodDecl(String name, String lowerName, int methodId, Visibility visibility,
                          boolean isStatic, boolean isAbstract, boolean isFinal,
                          RootCallTarget callTarget) {
            this(name, lowerName, methodId, visibility, isStatic, isAbstract, isFinal, callTarget, null);
        }

        /** The declared attributes, or an empty list for an internal method. */
        public List<PhpAttribute> attributes() {
            return signature == null ? List.of() : signature.attributes();
        }
    }

    /** One {@code case} of an enum. */
    public record EnumCaseDecl(String name, Object constValue, RootCallTarget valueTarget,
                               List<PhpAttribute> attributes) {
        public EnumCaseDecl {
            attributes = attributes == null ? List.of() : List.copyOf(attributes);
        }
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
    private final List<PhpAttribute> attributes;
    private final boolean isEnum;
    private final PhpType enumBackingType;
    private final List<EnumCaseDecl> enumCases;
    private final Shape rootShape;
    /** Where the declaration starts, for ReflectionClass; null for built-in classes. */
    private volatile String sourceFile;
    private volatile int startLine = -1;
    private volatile int endLine = -1;

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
        this(id, name, isInterface, isTrait, isAbstract, isFinal, isAnonymous, parentName, interfaceNames,
                traitUses, properties, constants, methods, methodSignatures, List.of(), false, null, List.of());
    }

    public PhpClassTemplate(long id, String name, boolean isInterface, boolean isTrait,
                            boolean isAbstract, boolean isFinal, boolean isAnonymous,
                            String parentName, List<String> interfaceNames, List<TraitUse> traitUses,
                            List<PropertyDecl> properties, List<ConstantDecl> constants,
                            Map<String, MethodDecl> methods, Map<String, MethodSignature> methodSignatures,
                            List<PhpAttribute> attributes, boolean isEnum, PhpType enumBackingType,
                            List<EnumCaseDecl> enumCases) {
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
        this.attributes = List.copyOf(attributes);
        this.isEnum = isEnum;
        this.enumBackingType = enumBackingType;
        this.enumCases = List.copyOf(enumCases);
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

    public List<PhpAttribute> attributes() {
        return attributes;
    }

    public boolean isEnum() {
        return isEnum;
    }

    /** The backing type of a backed enum, or null for a unit enum. */
    public PhpType enumBackingType() {
        return enumBackingType;
    }

    public List<EnumCaseDecl> enumCases() {
        return enumCases;
    }

    /** The enum case declared as {@code name}, or null. */
    public EnumCaseDecl enumCase(String name) {
        for (EnumCaseDecl c : enumCases) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        return null;
    }

    public Shape rootShape() {
        return rootShape;
    }

    /** Records the source location; called by the parser before the template is shared. */
    public PhpClassTemplate withSource(String file, int start, int end) {
        this.sourceFile = file;
        this.startLine = start;
        this.endLine = end;
        return this;
    }

    /** The file the class was declared in, or null for a built-in class. */
    public String sourceFile() {
        return sourceFile;
    }

    public int startLine() {
        return startLine;
    }

    public int endLine() {
        return endLine;
    }
}
