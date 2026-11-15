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
    private final Object[] captures;
    private final String magicMethodName;

    public PhpClosure(Kind kind, RootCallTarget callTarget, Builtins.Def builtin,
                      Object boundThis, PhpClass calledClass, Object[] captures, String magicMethodName) {
        this.kind = kind;
        this.callTarget = callTarget;
        this.builtin = builtin;
        this.boundThis = boundThis;
        this.calledClass = calledClass;
        this.captures = captures;
        this.magicMethodName = magicMethodName;
    }

    public static PhpClosure closure(RootCallTarget callTarget, Object boundThis, PhpClass calledClass, Object[] captures) {
        return new PhpClosure(Kind.CLOSURE, callTarget, null, boundThis, calledClass, captures, null);
    }

    public static PhpClosure userFunction(RootCallTarget callTarget) {
        return new PhpClosure(Kind.USER_FUNCTION, callTarget, null, PhpNull.INSTANCE, null, null, null);
    }

    public static PhpClosure method(RootCallTarget callTarget, Object boundThis, PhpClass calledClass) {
        return new PhpClosure(Kind.METHOD, callTarget, null, boundThis, calledClass, null, null);
    }

    public static PhpClosure magicCall(RootCallTarget callTarget, Object boundThis, PhpClass calledClass, String magicMethodName) {
        return new PhpClosure(Kind.MAGIC_CALL, callTarget, null, boundThis, calledClass, null, magicMethodName);
    }

    public static PhpClosure builtin(Builtins.Def builtin) {
        return new PhpClosure(Kind.BUILTIN, null, builtin, PhpNull.INSTANCE, null, null, null);
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

    public Object[] captures() {
        return captures;
    }

    public String magicMethodName() {
        return magicMethodName;
    }

    public PhpClosure copy() {
        Object[] capCopy = captures == null ? null : captures.clone();
        return new PhpClosure(kind, callTarget, builtin, boundThis, calledClass, capCopy, magicMethodName);
    }
}
