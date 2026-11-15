package dev.phpruntime.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.object.Shape;

import java.util.List;
import java.util.Map;

/**
 * Shared, immutable description of a parsed PHP class or interface.
 * Lives on the AST / engine across requests; per-request state lives in {@link PhpClass}.
 */
public final class PhpClassTemplate {

    public record PropertyDecl(String name, Visibility visibility, boolean isStatic,
                               Object constDefault, RootCallTarget defaultTarget) {
    }

    public record ConstantDecl(String name, Visibility visibility, boolean isFinal,
                               Object constValue, RootCallTarget valueTarget) {
    }

    public record MethodDecl(String name, String lowerName, int methodId, Visibility visibility,
                             boolean isStatic, boolean isAbstract, boolean isFinal,
                             RootCallTarget callTarget) {
    }

    private final long id;
    private final String name;
    private final String lowerName;
    private final boolean isInterface;
    private final boolean isAbstract;
    private final boolean isFinal;
    private final boolean isAnonymous;
    private final String parentName;
    private final List<String> interfaceNames;
    private final List<PropertyDecl> properties;
    private final List<ConstantDecl> constants;
    private final Map<String, MethodDecl> methods;
    private final Shape rootShape;

    public PhpClassTemplate(long id, String name, boolean isInterface, boolean isAbstract, boolean isFinal,
                            boolean isAnonymous, String parentName, List<String> interfaceNames,
                            List<PropertyDecl> properties, List<ConstantDecl> constants,
                            Map<String, MethodDecl> methods) {
        this.id = id;
        this.name = name;
        this.lowerName = name.toLowerCase();
        this.isInterface = isInterface;
        this.isAbstract = isAbstract;
        this.isFinal = isFinal;
        this.isAnonymous = isAnonymous;
        this.parentName = parentName;
        this.interfaceNames = List.copyOf(interfaceNames);
        this.properties = List.copyOf(properties);
        this.constants = List.copyOf(constants);
        this.methods = Map.copyOf(methods);
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

    public List<PropertyDecl> properties() {
        return properties;
    }

    public List<ConstantDecl> constants() {
        return constants;
    }

    public Map<String, MethodDecl> methods() {
        return methods;
    }

    public Shape rootShape() {
        return rootShape;
    }
}
