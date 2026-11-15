package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpClassTemplate;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;

/** Resolves a class target: literal name, {@code self}, {@code parent}, {@code static}, or dynamic expression. */
public final class ClassRefNode extends PhpNode {

    public enum Kind { NAMED, SELF, PARENT, STATIC, DYNAMIC, ANONYMOUS }

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

    public static ClassRefNode dynamic(PhpNode expr) {
        return new ClassRefNode(Kind.DYNAMIC, null, -1, -1, expr, null);
    }

    public static ClassRefNode anonymous(PhpClassTemplate template, int classId) {
        return new ClassRefNode(Kind.ANONYMOUS, template.name(), classId, -1, null, template);
    }

    public Kind kind() {
        return kind;
    }

    public boolean isForwarding() {
        return kind == Kind.SELF || kind == Kind.PARENT || kind == Kind.STATIC;
    }

    public String staticName() {
        return name;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return resolve(frame);
    }

    public PhpClass resolve(VirtualFrame frame) {
        PhpClass cls = resolveOrNull(frame);
        if (cls == null) {
            CompilerDirectives.transferToInterpreter();
            if (kind == Kind.PARENT) {
                throw new PhpError("Class \"" + name + "\" has no parent", this);
            }
            if (kind == Kind.STATIC) {
                throw new PhpError("Cannot use \"static\" when no class scope is active", this);
            }
            throw new PhpError("Class \"" + name + "\" not found", this);
        }
        return cls;
    }

    public PhpClass resolveOrNull(VirtualFrame frame) {
        return switch (kind) {
            case NAMED -> {
                PhpContext ctx = PhpContext.get(this);
                PhpClass c = ctx.classById(classId);
                yield c != null ? c : ctx.lookupOrAutoloadClass(name);
            }
            case SELF -> PhpContext.get(this).classById(classId);
            case PARENT -> {
                PhpClass cur = PhpContext.get(this).classById(classId);
                yield cur == null ? null : cur.parent();
            }
            case STATIC -> calledClassSlot >= 0 ? (PhpClass) frame.getObject(calledClassSlot) : null;
            case DYNAMIC -> {
                Object v = expr.execute(frame);
                if (v instanceof PhpObject o) {
                    yield o.phpClass();
                }
                String clsName = PhpOps.toStr(v);
                PhpClass c = PhpContext.get(this).lookupOrAutoloadClass(clsName);
                if (c == null && !clsName.isEmpty()) {
                    // Preserve name in error if resolve() throws.
                    yield throwMissing(clsName);
                }
                yield c;
            }
            case ANONYMOUS -> {
                PhpContext ctx = PhpContext.get(this);
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

    private PhpClass throwMissing(String clsName) {
        throw new PhpError("Class \"" + clsName + "\" not found", this);
    }

    /** Same as {@link #resolveOrNull} but returns null for missing class names without autoloading (for instanceof). */
    public PhpClass resolveForInstanceof(VirtualFrame frame) {
        if (kind == Kind.NAMED) {
            return PhpContext.get(this).classById(classId);
        }
        if (kind == Kind.DYNAMIC) {
            Object v = expr.execute(frame);
            if (v instanceof PhpObject o) {
                return o.phpClass();
            }
            if (v instanceof String s) {
                return PhpContext.get(this).lookupClass(s);
            }
            return null;
        }
        return resolveOrNull(frame);
    }
}
