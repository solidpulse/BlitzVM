package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.PhpContext;
import dev.phpruntime.builtins.Builtins;
import dev.phpruntime.nodes.PhpNode;
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
    private final String nsFnName;
    private final int nsFunctionId;
    private final String fnName;
    private final int functionId;

    @Child private PhpNode targetExpr;
    @Child private ClassRefNode classRef;
    private final String staticMemberName;
    private final int methodId;
    @Child private PhpNode dynamicMemberExpr;
    private final int thisSlot;
    private final int calledClassSlot;

    private FirstClassCallableNode(Mode mode, String nsFnName, int nsFunctionId, String fnName, int functionId,
                                   PhpNode targetExpr, ClassRefNode classRef, String staticMemberName, int methodId,
                                   PhpNode dynamicMemberExpr, int thisSlot, int calledClassSlot) {
        this.mode = mode;
        this.nsFnName = nsFnName;
        this.nsFunctionId = nsFunctionId;
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
        return new FirstClassCallableNode(Mode.FUNCTION, null, -1, name, functionId, null, null, null, -1, null, -1, -1);
    }

    public static FirstClassCallableNode forFunction(String nsName, int nsFunctionId, String name, int functionId) {
        return new FirstClassCallableNode(Mode.FUNCTION, nsName, nsFunctionId, name, functionId, null, null, null, -1, null, -1, -1);
    }

    public static FirstClassCallableNode forMethod(PhpNode targetExpr, String staticName, int methodId, PhpNode dynamicNameExpr) {
        return new FirstClassCallableNode(Mode.METHOD, null, -1, null, -1, targetExpr, null, staticName, methodId, dynamicNameExpr, -1, -1);
    }

    public static FirstClassCallableNode forStaticMethod(ClassRefNode classRef, String staticName, int methodId,
                                                         PhpNode dynamicNameExpr, int thisSlot, int calledClassSlot) {
        return new FirstClassCallableNode(Mode.STATIC_METHOD, null, -1, null, -1, null, classRef, staticName, methodId,
                dynamicNameExpr, thisSlot, calledClassSlot);
    }

    public static FirstClassCallableNode forCallable(PhpNode targetExpr, int thisSlot) {
        return new FirstClassCallableNode(Mode.CALLABLE, null, -1, null, -1, targetExpr, null, null, -1, null, thisSlot, -1);
    }

    @TruffleBoundary
    public static PhpObject createFunction(PhpContext ctx, String nsName, int nsFunctionId, String fnName, int functionId,
                                           Builtins.Def builtin, Node site) {
        if (nsFunctionId >= 0) {
            PhpFunction nsFn = ctx.functionById(nsFunctionId);
            if (nsFn != null) {
                return ctx.newClosure(PhpClosure.userFunction(nsFn.callTarget()));
            }
        }
        if (builtin != null) {
            return ctx.newClosure(PhpClosure.builtin(builtin));
        }
        PhpFunction fn = ctx.functionById(functionId);
        if (fn != null) {
            return ctx.newClosure(PhpClosure.userFunction(fn.callTarget()));
        }
        Builtins.Def def = Builtins.lookup(fnName);
        if (def != null) {
            return ctx.newClosure(PhpClosure.builtin(def));
        }
        CompilerDirectives.transferToInterpreter();
        throw new PhpError("Call to undefined function " + (nsName != null ? nsName : fnName) + "()", site);
    }

    @TruffleBoundary
    public static PhpObject createMethod(PhpContext ctx, Object target, String name, int methodId, Node site) {
        if (!(target instanceof PhpObject obj)) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Call to a member function " + name + "() on " + PhpOps.typeName(target), site);
        }
        PhpClass cls = obj.phpClass();
        PhpClass.MethodEntry entry = methodId >= 0 ? cls.methodById(methodId) : cls.lookupMethod(name);
        if (entry == null) {
            PhpClass.MethodEntry magic = cls.callMethod();
            if (magic != null && magic.callTarget() != null) {
                return ctx.newClosure(PhpClosure.magicCall(magic.callTarget(), obj, cls, name));
            }
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Call to undefined method " + cls.name() + "::" + name + "()", site);
        }
        if (entry.callTarget() == null) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Cannot call abstract method " + entry.declaringClass().name() + "::" + entry.name() + "()", site);
        }
        Object thisArg = entry.isStatic() ? PhpNull.INSTANCE : obj;
        return ctx.newClosure(PhpClosure.method(
                entry.callTarget(), thisArg, cls, entry.fromTrait() ? entry.declaringClass() : null));
    }

    @TruffleBoundary
    public static PhpObject createStaticMethod(PhpContext ctx, PhpClass targetClass, String name, int methodId,
                                               boolean forwarding, Object thisValue, Object calledClassValue, Node site) {
        PhpClass.MethodEntry entry = methodId >= 0 ? targetClass.methodById(methodId) : targetClass.lookupMethod(name);
        PhpClass calledArg = forwarding && calledClassValue instanceof PhpClass c ? c : targetClass;
        if (entry == null) {
            if (forwarding && thisValue instanceof PhpObject thisObj
                    && targetClass.callMethod() != null && targetClass.callMethod().callTarget() != null) {
                return ctx.newClosure(PhpClosure.magicCall(targetClass.callMethod().callTarget(), thisObj, calledArg, name));
            }
            PhpClass.MethodEntry magicStatic = targetClass.callStaticMethod();
            if (magicStatic != null && magicStatic.callTarget() != null) {
                return ctx.newClosure(PhpClosure.magicCall(magicStatic.callTarget(), PhpNull.INSTANCE, calledArg, name));
            }
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Call to undefined method " + targetClass.name() + "::" + name + "()", site);
        }
        if (entry.callTarget() == null) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Cannot call abstract method " + entry.declaringClass().name() + "::" + entry.name() + "()", site);
        }
        Object thisArg;
        if (entry.isStatic()) {
            thisArg = PhpNull.INSTANCE;
        } else if (thisValue instanceof PhpObject thisObj
                && thisObj.phpClass().isInstanceOf(entry.declaringClass())) {
            thisArg = thisObj;
        } else {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Non-static method " + entry.declaringClass().name() + "::" + entry.name()
                    + "() cannot be called statically", site);
        }
        return ctx.newClosure(PhpClosure.method(
                entry.callTarget(), thisArg, calledArg, entry.fromTrait() ? entry.declaringClass() : null));
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        switch (mode) {
            case FUNCTION -> b.emitFirstClassFunction(
                    nsFnName == null ? "" : nsFnName, nsFunctionId, fnName, functionId);
            case METHOD -> {
                b.beginBlock();
                com.oracle.truffle.api.bytecode.BytecodeLocal target = e.temp();
                b.beginStoreLocal(target);
                e.expr(targetExpr);
                b.endStoreLocal();
                b.beginFirstClassMethod(new dev.phpruntime.bytecode.PhpBytecodeRootNode.MethodSite(staticMemberName, methodId));
                b.emitLoadLocal(target);
                if (staticMemberName != null) {
                    b.emitLoadConstant(staticMemberName);
                } else {
                    e.expr(dynamicMemberExpr);
                }
                b.endFirstClassMethod();
                b.endBlock();
            }
            case STATIC_METHOD -> {
                b.beginBlock();
                b.beginFirstClassStaticMethod(
                        new dev.phpruntime.bytecode.PhpBytecodeRootNode.MethodSite(staticMemberName, methodId),
                        classRef.isForwarding(), calledClassSlot >= 0);
                e.classRef(classRef);
                if (staticMemberName != null) {
                    b.emitLoadConstant(staticMemberName);
                } else {
                    e.expr(dynamicMemberExpr);
                }
                e.loadSlotOrNull(thisSlot);
                e.loadSlotOrNull(calledClassSlot);
                b.endFirstClassStaticMethod();
                b.endBlock();
            }
            case CALLABLE -> {
                b.beginBlock();
                b.beginFirstClassCallable();
                e.expr(targetExpr);
                e.loadSlotOrNull(thisSlot);
                b.endFirstClassCallable();
                b.endBlock();
            }
        }
    }
}
