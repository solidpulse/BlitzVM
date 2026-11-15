package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.PhpContext;
import dev.phpruntime.builtins.Builtins;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpCallable;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpClosure;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpFunction;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;

/**
 * PHP 8.1+ first-class callable syntax:
 * {@code strlen(...)}, {@code $obj->method(...)}, {@code Foo::bar(...)}, or {@code $callable(...)}.
 */
public final class FirstClassCallableNode extends PhpNode {

    public enum Mode {
        FUNCTION,
        METHOD,
        STATIC_METHOD,
        CALLABLE
    }

    private final Mode mode;
    private final String fnName;
    private final int functionId;
    @CompilationFinal private Builtins.Def builtin;

    @Child private PhpNode targetExpr;
    @Child private ClassRefNode classRef;
    private final String staticMemberName;
    private final int methodId;
    @Child private PhpNode dynamicMemberExpr;
    private final int thisSlot;
    private final int calledClassSlot;

    private FirstClassCallableNode(Mode mode, String fnName, int functionId, PhpNode targetExpr,
                                   ClassRefNode classRef, String staticMemberName, int methodId,
                                   PhpNode dynamicMemberExpr, int thisSlot, int calledClassSlot) {
        this.mode = mode;
        this.fnName = fnName;
        this.functionId = functionId;
        this.targetExpr = targetExpr;
        this.classRef = classRef;
        this.staticMemberName = staticMemberName;
        this.methodId = methodId;
        this.dynamicMemberExpr = dynamicMemberExpr;
        this.thisSlot = thisSlot;
        this.calledClassSlot = calledClassSlot;
    }

    public static FirstClassCallableNode forFunction(String name, int functionId) {
        return new FirstClassCallableNode(Mode.FUNCTION, name, functionId, null, null, null, -1, null, -1, -1);
    }

    public static FirstClassCallableNode forMethod(PhpNode targetExpr, String staticName, int methodId, PhpNode dynamicNameExpr) {
        return new FirstClassCallableNode(Mode.METHOD, null, -1, targetExpr, null, staticName, methodId, dynamicNameExpr, -1, -1);
    }

    public static FirstClassCallableNode forStaticMethod(ClassRefNode classRef, String staticName, int methodId,
                                                         PhpNode dynamicNameExpr, int thisSlot, int calledClassSlot) {
        return new FirstClassCallableNode(Mode.STATIC_METHOD, null, -1, null, classRef, staticName, methodId,
                dynamicNameExpr, thisSlot, calledClassSlot);
    }

    public static FirstClassCallableNode forCallable(PhpNode targetExpr, int thisSlot) {
        return new FirstClassCallableNode(Mode.CALLABLE, null, -1, targetExpr, null, null, -1, null, thisSlot, -1);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        PhpContext ctx = PhpContext.get(this);
        return switch (mode) {
            case FUNCTION -> resolveFunction(ctx);
            case METHOD -> resolveMethod(frame, ctx);
            case STATIC_METHOD -> resolveStaticMethod(frame, ctx);
            case CALLABLE -> PhpCallable.toClosure(
                    ctx, targetExpr.execute(frame),
                    thisSlot >= 0 ? frame.getObject(thisSlot) : PhpNull.INSTANCE, this);
        };
    }

    private PhpObject resolveFunction(PhpContext ctx) {
        if (builtin != null) {
            return ctx.newClosure(PhpClosure.builtin(builtin));
        }
        PhpFunction fn = ctx.functionById(functionId);
        if (fn != null) {
            return ctx.newClosure(PhpClosure.userFunction(fn.callTarget()));
        }
        Builtins.Def def = resolveBuiltin();
        if (def != null) {
            return ctx.newClosure(PhpClosure.builtin(def));
        }
        throw new PhpError("Call to undefined function " + fnName + "()", this);
    }

    @TruffleBoundary
    private Builtins.Def resolveBuiltin() {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        java.util.concurrent.locks.Lock lock = getLock();
        lock.lock();
        try {
            if (builtin == null) {
                builtin = Builtins.lookup(fnName);
            }
            return builtin;
        } finally {
            lock.unlock();
        }
    }

    private PhpObject resolveMethod(VirtualFrame frame, PhpContext ctx) {
        Object target = targetExpr.execute(frame);
        String name = staticMemberName != null ? staticMemberName : PhpOps.toStr(dynamicMemberExpr.execute(frame));
        if (!(target instanceof PhpObject obj)) {
            throw new PhpError("Call to a member function " + name + "() on " + PhpOps.typeName(target), this);
        }
        PhpClass cls = obj.phpClass();
        PhpClass.MethodEntry entry = methodId >= 0 ? cls.methodById(methodId) : cls.lookupMethod(name);
        if (entry == null) {
            PhpClass.MethodEntry magic = cls.callMethod();
            if (magic != null && magic.callTarget() != null) {
                return ctx.newClosure(PhpClosure.magicCall(magic.callTarget(), obj, cls, name));
            }
            throw new PhpError("Call to undefined method " + cls.name() + "::" + name + "()", this);
        }
        if (entry.callTarget() == null) {
            throw new PhpError("Cannot call abstract method " + entry.declaringClass().name() + "::" + entry.name() + "()", this);
        }
        Object thisArg = entry.isStatic() ? PhpNull.INSTANCE : obj;
        return ctx.newClosure(PhpClosure.method(entry.callTarget(), thisArg, cls));
    }

    private PhpObject resolveStaticMethod(VirtualFrame frame, PhpContext ctx) {
        PhpClass targetClass = classRef.resolve(frame);
        String name = staticMemberName != null ? staticMemberName : PhpOps.toStr(dynamicMemberExpr.execute(frame));
        PhpClass.MethodEntry entry = methodId >= 0 ? targetClass.methodById(methodId) : targetClass.lookupMethod(name);
        PhpClass calledArg = classRef.isForwarding() && calledClassSlot >= 0
                ? (PhpClass) frame.getObject(calledClassSlot)
                : targetClass;
        if (entry == null) {
            if (classRef.isForwarding() && thisSlot >= 0
                    && frame.getObject(thisSlot) instanceof PhpObject thisObj
                    && targetClass.callMethod() != null && targetClass.callMethod().callTarget() != null) {
                return ctx.newClosure(PhpClosure.magicCall(targetClass.callMethod().callTarget(), thisObj, calledArg, name));
            }
            PhpClass.MethodEntry magicStatic = targetClass.callStaticMethod();
            if (magicStatic != null && magicStatic.callTarget() != null) {
                return ctx.newClosure(PhpClosure.magicCall(magicStatic.callTarget(), PhpNull.INSTANCE, calledArg, name));
            }
            throw new PhpError("Call to undefined method " + targetClass.name() + "::" + name + "()", this);
        }
        if (entry.callTarget() == null) {
            throw new PhpError("Cannot call abstract method " + entry.declaringClass().name() + "::" + entry.name() + "()", this);
        }
        Object thisArg;
        if (entry.isStatic()) {
            thisArg = PhpNull.INSTANCE;
        } else if (thisSlot >= 0
                && frame.getObject(thisSlot) instanceof PhpObject thisObj
                && thisObj.phpClass().isInstanceOf(entry.declaringClass())) {
            thisArg = thisObj;
        } else {
            throw new PhpError("Non-static method " + entry.declaringClass().name() + "::" + entry.name()
                    + "() cannot be called statically", this);
        }
        return ctx.newClosure(PhpClosure.method(entry.callTarget(), thisArg, calledArg));
    }
}
