package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpClassTemplate;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;

/** Resolves a class target: literal name, {@code self}, {@code parent}, {@code static}, or dynamic expression. */
public final class ClassRefNode extends PhpNode {

    public enum Kind { NAMED, SELF, PARENT, STATIC, TRAIT_SELF, TRAIT_PARENT, DYNAMIC, ANONYMOUS }

    private final Kind kind;
    private final String name;
    private final int classId;
    private final int calledClassSlot;
    @Child private PhpNode expr;
    private final PhpClassTemplate anonymousTemplate;

    private ClassRefNode(Kind kind, String name, int classId, int calledClassSlot, PhpNode expr,
                         PhpClassTemplate anonymousTemplate) {
        this.kind = kind;
        this.name = name;
        this.classId = classId;
        this.calledClassSlot = calledClassSlot;
        this.expr = expr;
        this.anonymousTemplate = anonymousTemplate;
    }

    public static ClassRefNode named(String name, int classId) {
        return new ClassRefNode(Kind.NAMED, name, classId, -1, null, null);
    }

    public static ClassRefNode self(String currentClass, int classId) {
        return new ClassRefNode(Kind.SELF, currentClass, classId, -1, null, null);
    }

    public static ClassRefNode parent(String currentClass, int classId) {
        return new ClassRefNode(Kind.PARENT, currentClass, classId, -1, null, null);
    }

    public static ClassRefNode staticRef(int calledClassSlot) {
        return new ClassRefNode(Kind.STATIC, "static", -1, calledClassSlot, null, null);
    }

    public static ClassRefNode traitSelf(int traitSelfSlot) {
        return new ClassRefNode(Kind.TRAIT_SELF, "self", -1, traitSelfSlot, null, null);
    }

    public static ClassRefNode traitParent(int traitSelfSlot) {
        return new ClassRefNode(Kind.TRAIT_PARENT, "parent", -1, traitSelfSlot, null, null);
    }

    public static ClassRefNode dynamic(PhpNode expr) {
        return new ClassRefNode(Kind.DYNAMIC, null, -1, -1, expr, null);
    }

    public static ClassRefNode anonymous(PhpClassTemplate template, int classId) {
        return new ClassRefNode(Kind.ANONYMOUS, template.name(), classId, -1, null, template);
    }

    public Kind kind() {
        return kind;
    }

    /** The frame slot STATIC and the TRAIT kinds read (the called class or trait self), else -1. */
    public int slot() {
        return kind == Kind.STATIC || kind == Kind.TRAIT_SELF || kind == Kind.TRAIT_PARENT ? calledClassSlot : -1;
    }

    public PhpNode dynamicExpr() {
        return expr;
    }

    /** A copy without its expression, for bytecode: see {@link #resolveValue}. */
    public ClassRefNode headless() {
        return new ClassRefNode(kind, name, classId, calledClassSlot, null, anonymousTemplate).at(line());
    }

    /**
     * Resolves the class with what it would read from the frame already read: for STATIC and
     * the TRAIT kinds the class in {@link #slot()}, for DYNAMIC the expression's value, else
     * unused.
     */
    public PhpClass resolveValue(Object operand) {
        PhpClass cls = resolveValueOrNull(operand);
        if (cls == null) {
            CompilerDirectives.transferToInterpreter();
            if (kind == Kind.PARENT) {
                throw new PhpError("Class \"" + name + "\" has no parent", this);
            }
            if (kind == Kind.TRAIT_PARENT) {
                throw new PhpError("Class \"" + (operand instanceof PhpClass c ? c.name() : "") + "\" has no parent", this);
            }
            if (kind == Kind.STATIC) {
                throw new PhpError("Cannot use \"static\" when no class scope is active", this);
            }
            throw new PhpError("Class \"" + name + "\" not found", this);
        }
        return cls;
    }

    public PhpClass resolveValueOrNull(Object operand) {
        PhpContext ctx = PhpContext.get(this);
        return switch (kind) {
            case NAMED -> {
                PhpClass c = ctx.classById(classId);
                yield c != null ? c : ctx.lookupOrAutoloadClass(name);
            }
            case SELF -> ctx.classById(classId);
            case PARENT -> {
                PhpClass cur = ctx.classById(classId);
                yield cur == null ? null : cur.parent();
            }
            case STATIC, TRAIT_SELF -> operand instanceof PhpClass c ? c : null;
            case TRAIT_PARENT -> operand instanceof PhpClass c ? c.parent() : null;
            case DYNAMIC -> {
                if (operand instanceof PhpObject o) {
                    yield o.phpClass();
                }
                String clsName = PhpOps.toStr(operand);
                PhpClass c = ctx.lookupOrAutoloadClass(clsName);
                if (c == null && !clsName.isEmpty()) {
                    yield throwMissing(clsName);
                }
                yield c;
            }
            case ANONYMOUS -> {
                PhpClass c = ctx.classById(classId);
                if (c != null) {
                    yield c;
                }
                try {
                    yield ctx.declareClass(anonymousTemplate);
                } catch (PhpError e) {
                    throw e.at(this);
                }
            }
        };
    }

    public boolean isForwarding() {
        return kind == Kind.SELF || kind == Kind.PARENT || kind == Kind.STATIC
                || kind == Kind.TRAIT_SELF || kind == Kind.TRAIT_PARENT;
    }

    public String staticName() {
        return name;
    }

    private PhpClass throwMissing(String clsName) {
        CompilerDirectives.transferToInterpreter();
        throw new PhpError("Class \"" + clsName + "\" not found", this);
    }

    /** Same as {@link #resolveValueOrNull} but returns null for missing class names without autoloading (for instanceof). */
    public PhpClass resolveForInstanceof(Object operand) {
        if (kind == Kind.NAMED) {
            return PhpContext.get(this).classById(classId);
        }
        if (kind == Kind.DYNAMIC) {
            if (operand instanceof PhpObject o) {
                return o.phpClass();
            }
            if (operand instanceof String s) {
                return PhpContext.get(this).lookupClass(s);
            }
            return null;
        }
        return resolveValueOrNull(operand);
    }
}
