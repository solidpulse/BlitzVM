package dev.phpruntime.parser;

import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.generator.*;
import dev.phpruntime.nodes.stmt.*;
import dev.phpruntime.nodes.var.LValueNode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Parse-time-only AST resumption pass. Ordinary functions never enter this pass. */
final class GeneratorLowering {
    private final Scope scope;
    private final dev.phpruntime.PhpLanguage language;

    GeneratorLowering(Scope scope, dev.phpruntime.PhpLanguage language) {
        this.scope = scope;
        this.language = language;
    }

    PhpNode lower(PhpNode node) {
        if (node == null || !containsYield(node)) {
            return node;
        }
        PhpNode result;
        if (node instanceof BlockNode) {
            PhpNode[] statements = nodes(node, "statements");
            for (int i = 0; i < statements.length; i++) {
                statements[i] = lower(statements[i]);
            }
            result = new ResumableBlockNode(statements, scope.resumeSlot());
        } else if (node instanceof IfNode || node instanceof GotoIfNode) {
            PhpNode condition = get(node, "condition");
            result = new ResumableIfNode(expression(condition), lower(get(node, "thenBranch")),
                    lower(get(node, "elseBranch")), scope.resumeSlot());
        } else if (node instanceof LoopStmtNode) {
            Node loop = get(node, "loop");
            Node body = loop.getChildren().iterator().next();
            result = new ResumableLoopNode(expression(get(node, "init")), expression(get(body, "condition")),
                    lower(get(body, "body")), expression(get(body, "update")), get(body, "conditionAfterBody"), scope.resumeSlot());
        } else if (node instanceof ForeachNode) {
            result = new ResumableForeachNode(language, expression(get(node, "subject")), get(node, "subjectLValue"),
                    get(node, "keyTarget"), get(node, "valueTarget"), get(node, "byRef"), lower(get(node, "body")), scope.objectSlot());
        } else if (node instanceof SwitchNode) {
            PhpNode[] cases = nodes(node, "caseValues");
            PhpNode[] bodies = nodes(node, "caseBodies");
            for (int i = 0; i < cases.length; i++) {
                cases[i] = expression(cases[i]);
                bodies[i] = lower(bodies[i]);
            }
            result = new ResumableSwitchNode(expression(get(node, "subject")), cases, bodies,
                    scope.resumeSlot(), scope.objectSlot());
        } else if (node instanceof TryCatchNode) {
            TryCatchNode.CatchClause[] catches = get(node, "catches");
            for (TryCatchNode.CatchClause clause : catches) {
                set(clause, "body", lower(get(clause, "body")));
            }
            result = new ResumableTryNode(lower(get(node, "tryBlock")), catches, lower(get(node, "finallyBlock")),
                    scope.resumeSlot(), scope.objectSlot());
        } else if (node instanceof EchoNode) {
            PhpNode[] values = nodes(node, "values");
            PhpNode[] echoes = new PhpNode[values.length];
            for (int i = 0; i < values.length; i++) {
                echoes[i] = expression(new EchoNode(new PhpNode[]{values[i]}).at(node.line()));
            }
            result = new ResumableBlockNode(echoes, scope.resumeSlot());
        } else {
            result = expression(node);
        }
        return result.at(node.line());
    }

    private PhpNode expression(PhpNode node) {
        if (node == null || !containsYield(node)) {
            return node;
        }
        if (node instanceof YieldNode || node instanceof YieldFromNode) {
            boolean nestedYield = false;
            for (Node child : node.getChildren()) {
                nestedYield |= containsYield(child);
            }
            if (!nestedYield) { return node; }
        }
        List<Integer> memos = new ArrayList<>();
        memoChildren(node, memos);
        return new ResumableExpressionNode(node, memos.stream().mapToInt(Integer::intValue).toArray()).at(node.line());
    }

    private void memoChildren(Node node, List<Integer> slots) {
        for (Field field : childFields(node)) {
            Object value = read(field, node);
            if (value instanceof PhpNode child) {
                if (child instanceof dev.phpruntime.nodes.var.LocalVarNode
                        && (field.getName().equals("target") || field.getName().equals("refTargets"))) {
                    continue;
                }
                memoChildren(child, slots);
                if (field.getType() != PhpNode.class && field.getType() != LValueNode.class) {
                    continue;
                }
                int slot = scope.objectSlot();
                slots.add(slot);
                if (child instanceof LValueNode lv) {
                    int resultSlot = scope.objectSlot();
                    slots.add(resultSlot);
                    write(field, node, new MemoLValueNode(lv, slot, resultSlot).at(child.line()));
                } else {
                    write(field, node, new MemoNode(child, slot).at(child.line()));
                }
            } else if (value instanceof PhpNode[] children) {
                if (field.getType() != PhpNode[].class && field.getType() != LValueNode[].class) {
                    for (PhpNode child : children) {
                        if (child != null) { memoChildren(child, slots); }
                    }
                    continue;
                }
                for (int i = 0; i < children.length; i++) {
                    PhpNode child = children[i];
                    if (child == null) {
                        continue;
                    }
                    memoChildren(child, slots);
                    int slot = scope.objectSlot();
                    slots.add(slot);
                    if (child instanceof LValueNode lv) {
                        int resultSlot = scope.objectSlot();
                        slots.add(resultSlot);
                        children[i] = new MemoLValueNode(lv, slot, resultSlot).at(child.line());
                    } else {
                        children[i] = new MemoNode(child, slot).at(child.line());
                    }
                }
            } else if (value instanceof Node[] children) {
                for (Node child : children) {
                    if (child != null) { memoChildren(child, slots); }
                }
            } else if (value instanceof Node child) {
                memoChildren(child, slots);
            }
        }
    }

    private static boolean containsYield(Node node) {
        if (node instanceof YieldNode || node instanceof YieldFromNode) {
            return true;
        }
        for (Node child : node.getChildren()) {
            if (containsYield(child)) {
                return true;
            }
        }
        return false;
    }

    private static List<Field> childFields(Node node) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> type = node.getClass(); type != null && type != Node.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.isAnnotationPresent(Node.Child.class) || field.isAnnotationPresent(Node.Children.class)) {
                    field.setAccessible(true);
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    private static PhpNode[] nodes(Object obj, String name) {
        return get(obj, name);
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Object obj, String name) {
        return (T) read(field(obj, name), obj);
    }

    private static void set(Object obj, String name, Object value) {
        write(field(obj, name), obj, value);
    }

    private static Field field(Object obj, String name) {
        for (Class<?> type = obj.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new IllegalStateException("Missing AST field " + obj.getClass() + "." + name);
    }

    private static Object read(Field field, Object obj) {
        try {
            return field.get(obj);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void write(Field field, Object obj, Object value) {
        try {
            field.set(obj, value);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
