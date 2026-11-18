package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.runtime.*;

/** Closure binding changes only the payload; the anonymous function AST and target are shared. */
public final class ClosureMethodRootNode extends RootNode {
    private final String name;

    public ClosureMethodRootNode(PhpLanguage language, String name) {
        super(language);
        this.name = name;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        PhpStrings.checkArguments(frame.getArguments(), "Closure::" + name);
        return PhpStrings.checkReturn(invoke(PhpContext.get(this), frame.getArguments()), "Closure::" + name);
    }

    @TruffleBoundary
    private Object invoke(PhpContext ctx, Object[] args) {
        int count = args.length - 2;
        int minimum = name.equals("bind") ? 2 : 1;
        int maximum = name.equals("call") ? Integer.MAX_VALUE : name.equals("bind") ? 3 : name.equals("bindTo") ? 2 : 1;
        if (count < minimum || count > maximum) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError(PhpError.ARGUMENT_COUNT, "Closure::" + name + "() expects "
                    + (minimum == maximum ? "exactly " : count < minimum ? "at least " : "at most ")
                    + (count < minimum ? minimum : maximum) + " argument" + ((count < minimum ? minimum : maximum) == 1 ? "" : "s")
                    + ", " + count + " given", this);
        }
        if (name.equals("fromCallable")) {
            if (args.length < 3) { throw new PhpError(PhpError.ARGUMENT_COUNT, "Closure::fromCallable() expects exactly 1 argument, 0 given", this); }
            Object value = args[2];
            PhpClass caller = callingScope();
            validateCallableVisibility(ctx, value, caller);
            try {
                return PhpCallable.toClosure(ctx, value, callingThis(), this);
            } catch (PhpError error) {
                com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                throw new PhpError(PhpError.TYPE_ERROR, "Failed to create closure from callable: " + error.detail(), this);
            }
        }
        PhpObject object;
        Object newThis;
        Object scopeArg;
        if (name.equals("bind")) {
            if (args.length < 4 || !(args[2] instanceof PhpObject obj) || obj.closure() == null) {
                com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                throw new PhpError(PhpError.TYPE_ERROR, "Closure::bind(): Argument #1 ($closure) must be of type Closure", this);
            }
            object = (PhpObject) args[2];
            newThis = args[3];
            scopeArg = args.length > 4 ? args[4] : "static";
        } else {
            object = (PhpObject) args[0];
            if (args.length < 3) { throw new PhpError(PhpError.ARGUMENT_COUNT, "Closure::" + name + "() expects at least 1 argument, 0 given", this); }
            newThis = args[2];
            scopeArg = args.length > 3 ? args[3] : "static";
        }
        if (newThis != PhpNull.INSTANCE && !(newThis instanceof PhpObject)) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError(PhpError.TYPE_ERROR, "Closure::" + name + "(): Argument #1 ($newThis) must be of type ?object, "
                    + PhpOps.typeDescription(newThis) + " given", this);
        }
        PhpClosure closure = object.closure();
        PhpFunctionRootNode signature = closure.callTarget() != null && dev.phpruntime.nodes.PhpFunctionRootNode.of(closure.callTarget().getRootNode()) instanceof dev.phpruntime.nodes.PhpFunctionRootNode root ? root : null;
        PhpClass oldScope = closure.traitSelf() != null ? closure.traitSelf()
                : signature != null && signature.declaringClass() != null ? ctx.lookupClass(signature.declaringClass()) : null;
        PhpClass newScope;
        if (name.equals("call")) {
            if (!(newThis instanceof PhpObject obj)) {
                com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                throw new PhpError(PhpError.TYPE_ERROR, "Closure::call(): Argument #1 ($newThis) must be of type object, null given", this);
            }
            newScope = obj.phpClass();
        } else if (scopeArg == PhpNull.INSTANCE) {
            newScope = null;
        } else if (scopeArg instanceof PhpObject obj) {
            newScope = obj.phpClass();
        } else if (scopeArg instanceof String scopeName) {
            if (scopeName.equals("static")) {
                newScope = oldScope;
            } else {
                newScope = ctx.lookupOrAutoloadClass(scopeName);
                if (newScope == null) {
                    ctx.warning("Class \"" + scopeName + "\" not found", 0);
                    return PhpNull.INSTANCE;
                }
            }
        } else {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError(PhpError.TYPE_ERROR, "Closure::" + name + "(): Argument #2 ($newScope) must be of type object|string|null", this);
        }
        if (newScope != null && (ctx.language().builtinClassTemplate(newScope.template().lowerName()) != null
                || newScope.template().id() == 0)) {
            return warning(ctx, "Cannot bind closure to scope of internal class " + newScope.name());
        }
        if (signature != null && signature.isStaticMethod() && newThis instanceof PhpObject) {
            return warning(ctx, "Cannot bind an instance to a static closure");
        }
        if (closure.kind() == PhpClosure.Kind.METHOD) {
            if (closure.boundThis() instanceof PhpObject) {
                if (newThis == PhpNull.INSTANCE) { return warning(ctx, "Cannot unbind $this of method"); }
                if (signature != null && newThis instanceof PhpObject obj
                        && !obj.phpClass().isInstanceOf(ctx.lookupClass(signature.declaringClass()))) {
                    return warning(ctx, "Cannot bind method " + signature.qualifiedCallableName() + "() to object of class " + obj.phpClass().name());
                }
            }
            if (newScope != oldScope) { return warning(ctx, "Cannot rebind scope of closure created from method"); }
        }
        if (newThis == PhpNull.INSTANCE && signature != null && signature.usesThis()) {
            return warning(ctx, "Cannot unbind $this of closure using $this");
        }
        PhpClass calledClass = newThis instanceof PhpObject obj ? obj.phpClass() : newScope;
        PhpClosure bound = closure.rebind(newThis, calledClass, newScope);
        PhpObject copy = ctx.newClosure(bound);
        ctx.copyClosureStatics(object, copy, name.equals("call"));
        if (name.equals("call")) {
            Object[] userArgs = java.util.Arrays.copyOfRange(args, 3, args.length);
            return PhpCallable.invokeClosure(ctx, null, this, copy, bound, userArgs);
        }
        return copy;
    }

    private Object warning(PhpContext ctx, String message) {
        ctx.warning(message + ", this will be an error in PHP 9", 0);
        return PhpNull.INSTANCE;
    }

    private void validateCallableVisibility(PhpContext ctx, Object value, PhpClass caller) {
        PhpClass cls = null;
        String methodName = null;
        if (value instanceof PhpArray array && array.size() == 2 && array.get(1L) instanceof String name) {
            methodName = name;
            Object receiver = array.get(0L);
            cls = receiver instanceof PhpObject obj ? obj.phpClass() : receiver instanceof String s ? ctx.lookupOrAutoloadClass(s) : null;
        } else if (value instanceof String callable && callable.contains("::")) {
            int separator = callable.indexOf("::");
            cls = ctx.lookupOrAutoloadClass(callable.substring(0, separator));
            methodName = callable.substring(separator + 2);
        }
        if (cls != null && methodName != null) {
            PhpClass.MethodEntry method = cls.lookupMethod(methodName);
            if (method != null && method.visibility() != Visibility.PUBLIC) {
                boolean allowed = caller != null && (method.visibility() == Visibility.PRIVATE ? caller == method.declaringClass()
                        : caller.isInstanceOf(method.declaringClass()) || method.declaringClass().isInstanceOf(caller));
                if (!allowed) {
                    com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                    throw new PhpError(PhpError.TYPE_ERROR, "Failed to create closure from callable: cannot access "
                            + (method.visibility() == Visibility.PRIVATE ? "private" : "protected") + " method "
                            + method.declaringClass().name() + "::" + method.name() + "()", this);
                }
            }
        }
    }

    private PhpClass callingScope() {
        return com.oracle.truffle.api.Truffle.getRuntime().iterateFrames(instance -> {
            if (instance.getCallTarget() instanceof com.oracle.truffle.api.RootCallTarget target
                    && dev.phpruntime.nodes.PhpFunctionRootNode.ofFrame(target.getRootNode()) instanceof PhpFunctionRootNode root) {
                var frame = dev.phpruntime.nodes.PhpFunctionRootNode.functionFrame(target.getRootNode(),
                        instance.getFrame(com.oracle.truffle.api.frame.FrameInstance.FrameAccess.READ_ONLY));
                if (root.traitSelfSlot() >= 0) { return (PhpClass) frame.getObject(root.traitSelfSlot()); }
                return root.declaringClass() != null ? PhpContext.get(this).lookupClass(root.declaringClass()) : null;
            }
            return null;
        });
    }

    private Object callingThis() {
        Object result = com.oracle.truffle.api.Truffle.getRuntime().iterateFrames(instance -> {
            if (instance.getCallTarget() instanceof com.oracle.truffle.api.RootCallTarget target
                    && dev.phpruntime.nodes.PhpFunctionRootNode.ofFrame(target.getRootNode()) instanceof PhpFunctionRootNode root) {
                var frame = dev.phpruntime.nodes.PhpFunctionRootNode.functionFrame(target.getRootNode(),
                        instance.getFrame(com.oracle.truffle.api.frame.FrameInstance.FrameAccess.READ_ONLY));
                return root.isMethod() || root.isClosure() ? frame.getArguments()[0] : PhpNull.INSTANCE;
            }
            return null;
        });
        return result == null ? PhpNull.INSTANCE : result;
    }

    @Override public String getName() { return "Closure::" + name; }
}
