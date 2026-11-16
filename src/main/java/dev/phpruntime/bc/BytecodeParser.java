package dev.phpruntime.bc;

import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeLabel;
import com.oracle.truffle.api.bytecode.BytecodeLocal;
import dev.phpruntime.bc.PhpBytecodeRootNode.Function;
import dev.phpruntime.bc.PhpBytecodeRootNodeGen.Builder;
import dev.phpruntime.parser.Lexer;
import dev.phpruntime.parser.Token;
import dev.phpruntime.parser.Token.Kind;
import dev.phpruntime.runtime.PhpNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Throwaway subset parser: these records exist only during compilation, never during execution. */
final class BytecodeParser {
    private interface Expr { }
    private record Constant(Object value) implements Expr { }
    private record Variable(String name) implements Expr { }
    private record Binary(String op, Expr left, Expr right) implements Expr { }
    private record Unary(String op, Expr value) implements Expr { }
    private record Index(Expr array, Expr key) implements Expr { }
    private record Assign(Expr target, Expr value) implements Expr { }
    private record Call(String name, List<Expr> arguments) implements Expr { }
    private record Array(List<Expr> keys, List<Expr> values) implements Expr { }
    private interface Stmt { }
    private record Block(List<Stmt> statements) implements Stmt { }
    private record Expression(Expr value) implements Stmt { }
    private record Echo(List<Expr> values) implements Stmt { }
    private record Return(Expr value) implements Stmt { }
    private record If(Expr condition, Stmt yes, Stmt no) implements Stmt { }
    private record Loop(Expr condition, Stmt body, List<Expr> updates) implements Stmt { }
    private record Foreach(Expr subject, String key, String value, Stmt body) implements Stmt { }
    private record Jump(boolean isBreak) implements Stmt { }
    private record Parameter(String name, Expr defaultValue) { }
    private record Declaration(String name, List<Parameter> parameters, Block body) { }
    private record LoopLabels(BytecodeLabel next, BytecodeLabel exit) { }

    private final BytecodeLanguage language;
    private final List<Token> tokens;
    private final Map<String, Declaration> declarations = new LinkedHashMap<>();
    private final Map<String, Function> functions = new LinkedHashMap<>();
    private int position;

    BytecodeParser(BytecodeLanguage language, String source) {
        this.language = language;
        tokens = Lexer.tokenize(source);
    }

    PhpBytecodeRootNode parse() {
        List<Stmt> main = new ArrayList<>();
        while (peek().kind() != Kind.EOF) {
            if (take("function")) {
                String name = identifier().toLowerCase(java.util.Locale.ROOT);
                expect("(");
                List<Parameter> parameters = new ArrayList<>();
                if (!take(")")) {
                    do {
                        String parameter = variable();
                        Expr value = take("=") ? expression(0) : null;
                        if (value != null && !(value instanceof Constant)) {
                            throw error("Only constant parameter defaults are supported");
                        }
                        parameters.add(new Parameter(parameter, value));
                    } while (take(","));
                    expect(")");
                }
                expect("{");
                Declaration declaration = new Declaration(name, parameters, block());
                if (declarations.putIfAbsent(name, declaration) != null) {
                    throw error("Duplicate function " + name);
                }
                int required = 0;
                for (int i = 0; i < parameters.size(); i++) {
                    if (parameters.get(i).defaultValue == null) { required = i + 1; }
                }
                functions.put(name, new Function(required, parameters.size()));
            } else {
                main.add(statement());
            }
        }
        for (Declaration declaration : declarations.values()) {
            functions.get(declaration.name).target = compile(declaration).getCallTarget();
        }
        return compile(new Declaration("<main-bytecode>", List.of(), new Block(main)));
    }

    private PhpBytecodeRootNode compile(Declaration declaration) {
        PhpBytecodeRootNode root = PhpBytecodeRootNodeGen.create(language, BytecodeConfig.DEFAULT, b -> {
            b.beginRoot();
            Emitter emitter = new Emitter(b);
            for (int i = 0; i < declaration.parameters.size(); i++) {
                Parameter parameter = declaration.parameters.get(i);
                b.beginStoreLocal(emitter.local(parameter.name));
                b.beginArgument(i);
                emitter.expr(parameter.defaultValue == null ? new Constant(PhpNull.INSTANCE) : parameter.defaultValue);
                b.endArgument();
                b.endStoreLocal();
            }
            emitter.stmt(declaration.body);
            b.beginReturn();
            b.emitLoadConstant(PhpNull.INSTANCE);
            b.endReturn();
            b.endRoot();
        }).getNode(0);
        root.name(declaration.name + "()");
        return root;
    }

    private Block block() {
        List<Stmt> statements = new ArrayList<>();
        while (!take("}")) {
            if (peek().kind() == Kind.EOF) { throw error("Unclosed block"); }
            statements.add(statement());
        }
        return new Block(statements);
    }

    private Stmt statement() {
        if (take("{")) { return block(); }
        if (take(";")) { return new Block(List.of()); }
        if (take("if")) {
            Expr condition = parenthesized();
            Stmt yes = statement();
            return new If(condition, yes, take("else") ? statement() : null);
        }
        if (take("while")) { return new Loop(parenthesized(), statement(), List.of()); }
        if (take("for")) {
            expect("(");
            List<Stmt> initial = new ArrayList<>();
            for (Expr expr : expressionsUntil(";")) { initial.add(new Expression(expr)); }
            Expr condition;
            if (take(";")) { condition = new Constant(true); }
            else { condition = expression(0); expect(";"); }
            List<Expr> updates = expressionsUntil(")");
            initial.add(new Loop(condition, statement(), updates));
            return new Block(initial);
        }
        if (take("foreach")) {
            expect("(");
            Expr subject = expression(0);
            expect("as");
            String first = variable();
            String key = null;
            String value = first;
            if (take("=>")) { key = first; value = variable(); }
            expect(")");
            return new Foreach(subject, key, value, statement());
        }
        if (take("return")) {
            Expr value = is(";") ? new Constant(PhpNull.INSTANCE) : expression(0);
            expect(";");
            return new Return(value);
        }
        if (take("echo")) { return new Echo(expressionsUntil(";")); }
        if (is("break") || is("continue")) {
            boolean isBreak = take("break");
            if (!isBreak) { expect("continue"); }
            expect(";");
            return new Jump(isBreak);
        }
        if (peek().kind() == Kind.INLINE_HTML) { return new Echo(List.of(new Constant(tokens.get(position++).text()))); }
        if (peek().kind() == Kind.CLOSE_TAG) { position++; return new Block(List.of()); }
        Expr value = expression(0);
        expect(";");
        return new Expression(value);
    }

    private List<Expr> expressionsUntil(String end) {
        List<Expr> result = new ArrayList<>();
        if (!take(end)) {
            do { result.add(expression(0)); } while (take(","));
            expect(end);
        }
        return result;
    }

    private Expr parenthesized() {
        expect("(");
        Expr value = expression(0);
        expect(")");
        return value;
    }

    private Expr expression(int minimum) {
        Expr left;
        if (take("!")) { left = new Unary("!", expression(9)); }
        else if (take("-")) { left = new Binary("-", new Constant(0L), expression(9)); }
        else if (take("(")) { left = expression(0); expect(")"); }
        else if (take("[")) {
            List<Expr> keys = new ArrayList<>();
            List<Expr> values = new ArrayList<>();
            if (!take("]")) {
                do {
                    Expr first = expression(0);
                    if (take("=>")) { keys.add(first); values.add(expression(0)); }
                    else { keys.add(null); values.add(first); }
                    if (is("]")) { break; }
                } while (take(",") && !is("]"));
                expect("]");
            }
            left = new Array(keys, values);
        } else {
            Token token = tokens.get(position++);
            left = switch (token.kind()) {
                case INT -> new Constant(Long.parseLong(token.text()));
                case FLOAT -> new Constant(Double.parseDouble(token.text()));
                case STRING -> new Constant(token.text());
                case VARIABLE -> new Variable(token.text());
                case IDENT -> switch (token.text().toLowerCase(java.util.Locale.ROOT)) {
                    case "true" -> new Constant(true);
                    case "false" -> new Constant(false);
                    case "null" -> new Constant(PhpNull.INSTANCE);
                    default -> {
                        expect("(");
                        yield new Call(token.text().toLowerCase(java.util.Locale.ROOT), expressionsUntil(")"));
                    }
                };
                default -> throw error("Unsupported expression " + token);
            };
        }
        while (true) {
            if (take("[")) {
                Expr key = take("]") ? null : expression(0);
                if (key != null) { expect("]"); }
                left = new Index(left, key);
                continue;
            }
            String op = peek().text();
            int precedence = precedence(op);
            if (precedence < minimum || precedence < 0) { break; }
            position++;
            boolean assign = precedence == 0;
            Expr right = expression(precedence + (assign ? 0 : 1));
            if (assign) {
                left = new Assign(left, op.equals("=") ? right : new Binary(op.substring(0, 1), left, right));
            } else { left = new Binary(op, left, right); }
        }
        return left;
    }

    private static int precedence(String op) {
        return switch (op) {
            case "=", "+=", "-=", "*=", ".=" -> 0;
            case "||" -> 1;
            case "&&" -> 2;
            case "==", "===", "!=", "!==" -> 3;
            case "<", "<=", ">", ">=" -> 4;
            case "." -> 5;
            case "+", "-" -> 6;
            case "*" -> 7;
            default -> -1;
        };
    }

    private Token peek() { return tokens.get(position); }
    private boolean is(String text) {
        Token token = peek();
        return (token.kind() == Kind.OP || token.kind() == Kind.IDENT) && token.text().equalsIgnoreCase(text);
    }
    private boolean take(String text) { if (!is(text)) { return false; } position++; return true; }
    private void expect(String text) { if (!take(text)) { throw error("Expected " + text + ", got " + peek()); } }
    private String identifier() {
        if (peek().kind() != Kind.IDENT) { throw error("Expected identifier"); }
        return tokens.get(position++).text();
    }
    private String variable() {
        if (peek().kind() != Kind.VARIABLE) { throw error("Expected variable"); }
        return tokens.get(position++).text();
    }
    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("Bytecode spike line " + peek().line() + ": " + message);
    }

    private final class Emitter {
        private final Builder b;
        private final Map<String, BytecodeLocal> locals = new LinkedHashMap<>();
        private final Deque<LoopLabels> loops = new ArrayDeque<>();

        Emitter(Builder b) { this.b = b; }
        BytecodeLocal local(String name) { return locals.computeIfAbsent(name, n -> b.createLocal(n, null)); }
        void truth(Expr value) { b.beginTruth(); expr(value); b.endTruth(); }
        void copy(Expr value) { b.beginCopy(); expr(value); b.endCopy(); }

        void stmt(Stmt statement) {
            if (statement instanceof Block block) {
                b.beginBlock();
                for (Stmt child : block.statements) { stmt(child); }
                b.endBlock();
            } else if (statement instanceof Expression expression) { expr(expression.value); }
            else if (statement instanceof Echo echo) {
                for (Expr value : echo.values) { b.beginEcho(); expr(value); b.endEcho(); }
            } else if (statement instanceof Return ret) {
                b.beginReturn(); copy(ret.value); b.endReturn();
            } else if (statement instanceof If branch) {
                if (branch.no == null) { b.beginIfThen(); } else { b.beginIfThenElse(); }
                truth(branch.condition);
                stmt(branch.yes);
                if (branch.no == null) { b.endIfThen(); }
                else { stmt(branch.no); b.endIfThenElse(); }
            } else if (statement instanceof Loop loop) {
                b.beginBlock();
                BytecodeLabel exit = b.createLabel();
                b.beginWhile();
                truth(loop.condition);
                b.beginBlock();
                BytecodeLabel next = b.createLabel();
                loops.push(new LoopLabels(next, exit));
                stmt(loop.body);
                loops.pop();
                b.emitLabel(next);
                for (Expr update : loop.updates) { expr(update); }
                b.endBlock();
                b.endWhile();
                b.emitLabel(exit);
                b.endBlock();
            } else if (statement instanceof Foreach loop) {
                b.beginBlock();
                BytecodeLocal cursor = b.createLocal();
                b.beginStoreLocal(cursor);
                b.beginIterate(); expr(loop.subject); b.endIterate();
                b.endStoreLocal();
                b.beginTryFinally(() -> {
                    b.beginEndIterate(); b.emitLoadLocal(cursor); b.endEndIterate();
                });
                b.beginBlock();
                BytecodeLabel exit = b.createLabel();
                b.beginWhile();
                b.beginAdvance(); b.emitLoadLocal(cursor); b.endAdvance();
                b.beginBlock();
                BytecodeLabel next = b.createLabel();
                if (loop.key != null) {
                    b.beginStoreLocal(local(loop.key));
                    b.beginIterKey(); b.emitLoadLocal(cursor); b.endIterKey();
                    b.endStoreLocal();
                }
                b.beginStoreLocal(local(loop.value));
                b.beginIterValue(); b.emitLoadLocal(cursor); b.endIterValue();
                b.endStoreLocal();
                loops.push(new LoopLabels(next, exit));
                stmt(loop.body);
                loops.pop();
                b.emitLabel(next);
                b.endBlock();
                b.endWhile();
                b.emitLabel(exit);
                b.endBlock();
                b.endTryFinally();
                b.endBlock();
            } else if (statement instanceof Jump jump) {
                if (loops.isEmpty()) { throw error("Jump outside loop"); }
                b.emitBranch(jump.isBreak ? loops.peek().exit : loops.peek().next);
            } else { throw new AssertionError(statement); }
        }

        void expr(Expr expression) {
            if (expression instanceof Constant constant) { b.emitLoadConstant(constant.value); }
            else if (expression instanceof Variable variable) { b.emitLoadLocal(local(variable.name)); }
            else if (expression instanceof Index index) {
                if (index.key == null) { throw error("Append index cannot be read"); }
                b.beginIndex(); expr(index.array); expr(index.key); b.endIndex();
            } else if (expression instanceof Assign assignment) {
                if (assignment.target instanceof Variable variable) {
                    BytecodeLocal target = local(variable.name);
                    b.beginBlock();
                    b.beginStoreLocal(target); copy(assignment.value); b.endStoreLocal();
                    b.emitLoadLocal(target);
                    b.endBlock();
                } else if (assignment.target instanceof Index index) {
                    List<Expr> keys = new ArrayList<>();
                    Expr base = index;
                    while (base instanceof Index part) { keys.add(0, part.key); base = part.array; }
                    if (!(base instanceof Variable variable)) { throw error("Offset write requires a local base"); }
                    BytecodeLocal target = local(variable.name);
                    BytecodeLocal evaluatedKeys = b.createLocal();
                    BytecodeLocal value = b.createLocal();
                    b.beginBlock();
                    b.beginStoreLocal(evaluatedKeys);
                    b.beginKeys();
                    for (Expr key : keys) { if (key == null) { b.emitLoadNull(); } else { expr(key); } }
                    b.endKeys();
                    b.endStoreLocal();
                    b.beginStoreLocal(value); expr(assignment.value); b.endStoreLocal();
                    b.beginStoreLocal(target);
                    b.beginWritePath(); b.emitLoadLocal(target); b.emitLoadLocal(evaluatedKeys); b.emitLoadLocal(value);
                    b.endWritePath(); b.endStoreLocal();
                    b.emitLoadLocal(value);
                    b.endBlock();
                } else { throw error("Unsupported assignment target"); }
            } else if (expression instanceof Unary unary) {
                b.beginNot(); truth(unary.value); b.endNot();
            } else if (expression instanceof Binary binary) { binary(binary); }
            else if (expression instanceof Array array) {
                BytecodeLocal temp = b.createLocal();
                b.beginBlock();
                b.beginStoreLocal(temp); b.emitNewArray(); b.endStoreLocal();
                for (int i = 0; i < array.values.size(); i++) {
                    b.beginStoreLocal(temp);
                    Expr key = array.keys.get(i);
                    if (key == null) { b.beginLiteralAppend(); } else { b.beginLiteralPut(); }
                    b.emitLoadLocal(temp);
                    if (key != null) { expr(key); }
                    expr(array.values.get(i));
                    if (key == null) { b.endLiteralAppend(); } else { b.endLiteralPut(); }
                    b.endStoreLocal();
                }
                b.emitLoadLocal(temp);
                b.endBlock();
            } else if (expression instanceof Call call) { call(call); }
            else { throw new AssertionError(expression); }
        }

        private void binary(Binary binary) {
            String op = binary.op;
            if (op.equals("||") || op.equals("&&")) {
                b.beginConditional();
                truth(binary.left);
                if (op.equals("||")) { b.emitLoadConstant(true); truth(binary.right); }
                else { truth(binary.right); b.emitLoadConstant(false); }
                b.endConditional();
                return;
            }
            if (op.equals("!=") || op.equals("!==")) {
                b.beginNot();
                binary(new Binary(op.equals("!=") ? "==" : "===", binary.left, binary.right));
                b.endNot();
                return;
            }
            if (op.equals(">") || op.equals(">=")) {
                throw error("Use < or <= in the spike (avoids swapping side-effect evaluation)");
            }
            switch (op) {
                case "+" -> b.beginAdd();
                case "-" -> b.beginSubtract();
                case "*" -> b.beginMultiply();
                case "<" -> b.beginLess();
                case "<=" -> b.beginLessEqual();
                case "==" -> b.beginEqual();
                case "===" -> b.beginIdentical();
                case "." -> b.beginConcat();
                default -> throw error("Unsupported operator " + op);
            }
            expr(binary.left); expr(binary.right);
            switch (op) {
                case "+" -> b.endAdd();
                case "-" -> b.endSubtract();
                case "*" -> b.endMultiply();
                case "<" -> b.endLess();
                case "<=" -> b.endLessEqual();
                case "==" -> b.endEqual();
                case "===" -> b.endIdentical();
                case "." -> b.endConcat();
                default -> throw new AssertionError(op);
            }
        }

        private void call(Call call) {
            Function function = functions.get(call.name);
            if (function != null) {
                b.beginCall(function);
                for (Expr argument : call.arguments) { copy(argument); }
                b.endCall();
                return;
            }
            if (call.name.equals("isset")) {
                if (call.arguments.isEmpty()) { throw error("isset requires arguments"); }
                Expr result = new Constant(true);
                for (Expr argument : call.arguments) {
                    result = new Binary("&&", result, new Call("__isset", List.of(argument)));
                }
                expr(result);
                return;
            }
            int arity = call.name.equals("array_key_exists") ? 2 : 1;
            if (call.arguments.size() != arity) { throw error("Wrong builtin arity " + call.name); }
            switch (call.name) {
                case "count" -> b.beginCount();
                case "is_array" -> b.beginIsArray();
                case "is_string" -> b.beginIsString();
                case "is_int" -> b.beginIsInt();
                case "strlen" -> b.beginStrlen();
                case "array_key_exists" -> b.beginKeyExists();
                case "__isset" -> b.beginIsset();
                case "hrtime" -> b.beginHrtime();
                default -> throw error("Unsupported builtin " + call.name);
            }
            for (Expr argument : call.arguments) { expr(argument); }
            switch (call.name) {
                case "count" -> b.endCount();
                case "is_array" -> b.endIsArray();
                case "is_string" -> b.endIsString();
                case "is_int" -> b.endIsInt();
                case "strlen" -> b.endStrlen();
                case "array_key_exists" -> b.endKeyExists();
                case "__isset" -> b.endIsset();
                case "hrtime" -> b.endHrtime();
                default -> throw new AssertionError(call.name);
            }
        }
    }
}
