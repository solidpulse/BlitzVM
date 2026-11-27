package dev.phpruntime.runtime;

import com.oracle.truffle.api.RootCallTarget;
import dev.phpruntime.builtins.Builtins;

/**
 * Runtime payload for a PHP {@code Closure} object (anonymous functions, arrow functions,
 * and first-class callables). Stored on a {@link PhpObject} of class {@code Closure}.
 */
public final class PhpClosure {

    public enum Kind {
        CLOSURE,
        USER_FUNCTION,
        METHOD,
        MAGIC_CALL,
        BUILTIN
    }

    private final Kind kind;
    private final RootCallTarget callTarget;
    private final Builtins.Def builtin;
    private final Object boundThis;
    private final PhpClass calledClass;
    private final PhpClass traitSelf;
    private final Object[] captures;
    private final String magicMethodName;
    /** The linked method a first-class callable of a Java method refers to, else null. */
    private final PhpClass.MethodEntry methodEntry;

    public PhpClosure(Kind kind, RootCallTarget callTarget, Builtins.Def builtin,
                      Object boundThis, PhpClass calledClass, Object[] captures, String magicMethodName) {
        this(kind, callTarget, builtin, boundThis, calledClass, null, captures, magicMethodName);
    }

    public PhpClosure(Kind kind, RootCallTarget callTarget, Builtins.Def builtin,
                      Object boundThis, PhpClass calledClass, PhpClass traitSelf,
                      Object[] captures, String magicMethodName) {
        this(kind, callTarget, builtin, boundThis, calledClass, traitSelf, captures, magicMethodName, null);
    }

    public PhpClosure(Kind kind, RootCallTarget callTarget, Builtins.Def builtin,
                      Object boundThis, PhpClass calledClass, PhpClass traitSelf,
                      Object[] captures, String magicMethodName, PhpClass.MethodEntry methodEntry) {
        this.kind = kind;
        this.callTarget = callTarget;
        this.builtin = builtin;
        this.boundThis = boundThis;
        this.calledClass = calledClass;
        this.traitSelf = traitSelf;
        this.captures = captures;
        this.magicMethodName = magicMethodName;
        this.methodEntry = methodEntry;
    }

    public static PhpClosure closure(RootCallTarget callTarget, Object boundThis, PhpClass calledClass, Object[] captures) {
        return new PhpClosure(Kind.CLOSURE, callTarget, null, boundThis, calledClass, null, captures, null);
    }

    public static PhpClosure closure(RootCallTarget callTarget, Object boundThis, PhpClass calledClass,
                                     PhpClass traitSelf, Object[] captures) {
        return new PhpClosure(Kind.CLOSURE, callTarget, null, boundThis, calledClass, traitSelf, captures, null);
    }

    public static PhpClosure userFunction(RootCallTarget callTarget) {
        return new PhpClosure(Kind.USER_FUNCTION, callTarget, null, PhpNull.INSTANCE, null, null, null, null);
    }

    public static PhpClosure method(RootCallTarget callTarget, Object boundThis, PhpClass calledClass) {
        return new PhpClosure(Kind.METHOD, callTarget, null, boundThis, calledClass, null, null, null);
    }

    public static PhpClosure method(RootCallTarget callTarget, Object boundThis, PhpClass calledClass, PhpClass traitSelf) {
        return new PhpClosure(Kind.METHOD, callTarget, null, boundThis, calledClass, traitSelf, null, null);
    }

    public static PhpClosure method(RootCallTarget callTarget, Object boundThis, PhpClass calledClass,
                                    PhpClass traitSelf, PhpClass.MethodEntry methodEntry) {
        return new PhpClosure(Kind.METHOD, callTarget, null, boundThis, calledClass, traitSelf, null, null, methodEntry);
    }

    public static PhpClosure magicCall(RootCallTarget callTarget, Object boundThis, PhpClass calledClass, String magicMethodName) {
        return new PhpClosure(Kind.MAGIC_CALL, callTarget, null, boundThis, calledClass, null, null, magicMethodName);
    }

    /** A magic method declared in a trait runs for the using class passed as traitSelf. */
    public static PhpClosure magicCall(RootCallTarget callTarget, Object boundThis, PhpClass calledClass,
                                       PhpClass traitSelf, String magicMethodName) {
        return new PhpClosure(Kind.MAGIC_CALL, callTarget, null, boundThis, calledClass, traitSelf, null, magicMethodName);
    }

    public static PhpClosure builtin(Builtins.Def builtin) {
        return new PhpClosure(Kind.BUILTIN, null, builtin, PhpNull.INSTANCE, null, null, null, null);
    }

    public Kind kind() {
        return kind;
    }

    public RootCallTarget callTarget() {
        return callTarget;
    }

    public Builtins.Def builtin() {
        return builtin;
    }

    public Object boundThis() {
        return boundThis;
    }

    public PhpClass calledClass() {
        return calledClass;
    }

    public PhpClass traitSelf() {
        return traitSelf;
    }

    public Object[] captures() {
        return captures;
    }

    public String magicMethodName() {
        return magicMethodName;
    }

    /** The Java method this first-class callable refers to, or null for guest closures. */
    public PhpClass.MethodEntry methodEntry() {
        return methodEntry;
    }

    public PhpClosure copy() {
        Object[] capCopy = captures == null ? null : captures.clone();
        return new PhpClosure(kind, callTarget, builtin, boundThis, calledClass, traitSelf, capCopy, magicMethodName,
                methodEntry);
    }

    public PhpClosure rebind(Object newThis, PhpClass newCalledClass, PhpClass newScope) {
        return new PhpClosure(kind, callTarget, builtin, newThis, newCalledClass, kind == Kind.CLOSURE ? newScope : traitSelf,
                captures == null ? null : captures.clone(), magicMethodName, methodEntry);
    }
}
