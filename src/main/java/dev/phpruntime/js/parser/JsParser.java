package dev.phpruntime.js.parser;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.source.Source;
import dev.phpruntime.js.JsContext;
import dev.phpruntime.js.JsLanguage;
import dev.phpruntime.js.nodes.JsCalls;
import dev.phpruntime.js.nodes.JsFunctionRootNode;
import dev.phpruntime.js.nodes.JsModuleRootNode;
import dev.phpruntime.js.nodes.JsNode;
import dev.phpruntime.js.nodes.JsOperators;
import dev.phpruntime.js.nodes.JsOperatorsFactory;
import dev.phpruntime.js.nodes.JsStatements;
import dev.phpruntime.js.nodes.JsVariables;
import dev.phpruntime.js.parser.JsLexer.Kind;
import dev.phpruntime.js.parser.JsLexer.TemplatePart;
import dev.phpruntime.js.parser.JsLexer.Token;
import dev.phpruntime.js.runtime.JsValues.Null;
import dev.phpruntime.js.runtime.JsValues.Undefined;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recursive-descent parser with Pratt-style binary operators, producing Truffle nodes
 * directly. Names resolve at parse time: a function's own locals become frame slots,
 * everything else is a top-level binding in the module frame.
 */
public final class JsParser {

    private static final Set<String> RESERVED = Set.of(
            "function", "async", "await", "return", "if", "else", "while", "for", "break", "continue",
            "const", "let", "var", "true", "false", "null", "new", "class", "this", "typeof", "switch",
            "try", "catch", "throw", "do", "in", "of", "delete", "instanceof", "yield");

    /** A function (or the module) being parsed. */
    private static final class Scope {
        final Scope parent;
        final boolean module;
        final FrameDescriptor.Builder frame = FrameDescriptor.newBuilder();
        final Map<String, Integer> slots = new HashMap<>();
        final List<JsNode> hoisted = new ArrayList<>();
        final Map<Integer, JsVariables.SlotKind> kinds = new HashMap<>();

        Scope(Scope parent, boolean module) {
            this.parent = parent;
            this.module = module;
        }

        int declare(String name) {
            return slots.computeIfAbsent(name, n -> frame.addSlot(FrameSlotKind.Object, n, null));
        }

        JsVariables.SlotKind kind(int slot) {
            return kinds.computeIfAbsent(slot, s -> new JsVariables.SlotKind());
        }

        JsVariables.Local local(String name) {
            int slot = declare(name);
            return new JsVariables.Local(slot, name, kind(slot));
        }
    }

    private final JsLanguage language;
    private final Source source;
    private List<Token> tokens;
    private int pos;
    private Scope scope;
    private Scope moduleScope;

    public JsParser(JsLanguage language, Source source) {
        this.language = language;
        this.source = source;
    }

    public JsModuleRootNode parseModule() {
        try {
            tokens = new JsLexer(source.getCharacters().toString(), 1).lex();
            pos = 0;
            moduleScope = new Scope(null, true);
            scope = moduleScope;
            String[] names = JsContext.GLOBALS.toArray(new String[0]);
            int[] slots = new int[names.length];
            for (int i = 0; i < names.length; i++) {
                slots[i] = moduleScope.declare(names[i]);
            }
            List<JsNode> body = new ArrayList<>();
            while (peek().kind() != Kind.EOF) {
                body.add(statement());
            }
            JsNode block = block(moduleScope, body);
            return new JsModuleRootNode(language, moduleScope.frame.build(), block, names, slots, source);
        } catch (JsParseError e) {
            throw e.withSource(source);
        }
    }

    // ---------------------------------------------------------------- tokens

    private Token peek() {
        return tokens.get(pos);
    }

    private Token peek(int ahead) {
        return tokens.get(Math.min(pos + ahead, tokens.size() - 1));
    }

    private Token advance() {
        return tokens.get(pos++);
    }

    private boolean at(String text) {
        return peek().is(text);
    }

    private boolean accept(String text) {
        if (at(text)) {
            pos++;
            return true;
        }
        return false;
    }

    private Token expect(String text) {
        if (!at(text)) {
            throw error("Unexpected token '" + peek().text() + "', expected '" + text + "'");
        }
        return advance();
    }

    private String identifier() {
        Token t = advance();
        if (t.kind() != Kind.IDENT || RESERVED.contains(t.text())) {
            throw new JsParseError("Unexpected token '" + t.text() + "', expected an identifier", t.line());
        }
        return t.text();
    }

    private void semicolon() {
        accept(";");
    }

    private JsParseError error(String message) {
        return new JsParseError(message, peek().line());
    }

    private <T extends JsNode> T at(T node, int line) {
        return node.at(source, line);
    }

    // ------------------------------------------------------------ statements

    private JsNode block(Scope s, List<JsNode> statements) {
        List<JsNode> all = new ArrayList<>(s.hoisted);
        all.addAll(statements);
        return new JsStatements.Block(all.toArray(new JsNode[0]));
    }

    private JsNode statement() {
        Token t = peek();
        int line = t.line();
        if (t.kind() == Kind.PUNCT) {
            if (accept(";")) {
                return new JsStatements.Empty();
            }
            if (at("{")) {
                return blockStatement();
            }
        }
        if (t.kind() == Kind.IDENT) {
            switch (t.text()) {
                case "function":
                    return functionDeclaration(false);
                case "async":
                    if (peek(1).is("function")) {
                        advance();
                        return functionDeclaration(true);
                    }
                    break;
                case "const", "let", "var": {
                    advance();
                    JsNode decl = declarations(line);
                    semicolon();
                    return decl;
                }
                case "if": {
                    advance();
                    expect("(");
                    JsNode cond = expression();
                    expect(")");
                    JsNode then = statement();
                    JsNode otherwise = accept("else") ? statement() : null;
                    return at(new JsStatements.If(cond, then, otherwise), line);
                }
                case "while": {
                    advance();
                    expect("(");
                    JsNode cond = expression();
                    expect(")");
                    return at(new JsStatements.Loop(null, cond, null, statement()), line);
                }
                case "for":
                    return forStatement();
                case "return": {
                    advance();
                    if (scope.module) {
                        throw new JsParseError("Illegal return statement", line);
                    }
                    JsNode value = at(";") || at("}") ? null : expression();
                    semicolon();
                    return at(new JsStatements.Return(value), line);
                }
                case "break":
                    advance();
                    semicolon();
                    return new JsStatements.Break();
                case "continue":
                    advance();
                    semicolon();
                    return new JsStatements.Continue();
                case "class", "switch", "try", "throw", "do", "new":
                    throw new JsParseError("'" + t.text() + "' is not supported by this JavaScript subset yet", line);
                default:
            }
        }
        JsNode e = expression();
        semicolon();
        return e;
    }

    private JsNode blockStatement() {
        expect("{");
        List<JsNode> list = new ArrayList<>();
        while (!at("}")) {
            if (peek().kind() == Kind.EOF) {
                throw error("Unexpected end of input");
            }
            list.add(statement());
        }
        expect("}");
        return new JsStatements.Block(list.toArray(new JsNode[0]));
    }

    private JsNode declarations(int line) {
        List<JsNode> list = new ArrayList<>();
        do {
            if (at("{") || at("[")) {
                throw error("Destructuring is not supported by this JavaScript subset yet");
            }
            String name = identifier();
            JsVariables.Target target = declareTarget(name);
            JsNode init = accept("=") ? assignment() : new JsOperators.Constant(Undefined.INSTANCE);
            list.add(at(new JsVariables.Assign(target, init), line));
        } while (accept(","));
        return list.size() == 1 ? list.get(0) : new JsStatements.Block(list.toArray(new JsNode[0]));
    }

    private JsVariables.Target declareTarget(String name) {
        return scope.local(name);
    }

    private JsNode forStatement() {
        int line = advance().line();
        expect("(");
        JsNode init = null;
        if (!at(";")) {
            if (at("let") || at("const") || at("var")) {
                int declLine = advance().line();
                init = declarations(declLine);
            } else {
                init = expression();
            }
        }
        if (at("of") || at("in")) {
            throw error("for..of and for..in are not supported by this JavaScript subset yet");
        }
        expect(";");
        JsNode cond = at(";") ? null : expression();
        expect(";");
        JsNode update = at(")") ? null : expression();
        expect(")");
        return at(new JsStatements.Loop(init, cond, update, statement()), line);
    }

    private JsNode functionDeclaration(boolean isAsync) {
        int line = advance().line(); // 'function'
        String name = identifier();
        JsVariables.Target target = declareTarget(name);
        JsNode fn = functionRest(name, line);
        scope.hoisted.add(new JsVariables.Assign(target, fn));
        return new JsStatements.Empty();
    }

    /** Parameter list and body after the name of a function. */
    private JsNode functionRest(String name, int line) {
        expect("(");
        List<String> params = new ArrayList<>();
        while (!at(")")) {
            params.add(identifier());
            if (!accept(",")) {
                break;
            }
        }
        expect(")");
        return function(name, params, line, false);
    }

    /** Parses a body ({@code { ... }}, or an expression for arrows) in a new function scope. */
    private JsNode function(String name, List<String> params, int line, boolean expressionBody) {
        Scope outer = scope;
        scope = new Scope(outer, false);
        int[] paramSlots = new int[params.size()];
        JsVariables.SlotKind[] paramKinds = new JsVariables.SlotKind[params.size()];
        for (int i = 0; i < params.size(); i++) {
            paramSlots[i] = scope.declare(params.get(i));
            paramKinds[i] = scope.kind(paramSlots[i]);
        }
        JsNode body;
        if (expressionBody) {
            int exprLine = peek().line();
            body = block(scope, List.of(at(new JsStatements.Return(assignment()), exprLine)));
        } else {
            expect("{");
            List<JsNode> list = new ArrayList<>();
            while (!at("}")) {
                if (peek().kind() == Kind.EOF) {
                    throw error("Unexpected end of input");
                }
                list.add(statement());
            }
            expect("}");
            body = block(scope, list);
        }
        JsFunctionRootNode root = new JsFunctionRootNode(language, scope.frame.build(), body, paramSlots, paramKinds, name, source, line);
        scope = outer;
        return at(new JsCalls.FunctionLiteral(root, name, outer.module), line);
    }

    // ----------------------------------------------------------- expressions

    private JsNode expression() {
        JsNode e = assignment();
        if (at(",")) {
            throw error("The comma operator is not supported by this JavaScript subset yet");
        }
        return e;
    }

    private JsNode assignment() {
        if (isArrowAhead()) {
            return arrow();
        }
        int line = peek().line();
        JsNode left = conditional();
        Token t = peek();
        if (t.kind() == Kind.PUNCT) {
            String op = t.text();
            if (op.equals("=") || op.equals("+=") || op.equals("-=") || op.equals("*=") || op.equals("/=") || op.equals("%=")) {
                if (!(left instanceof JsVariables.Target target)) {
                    throw new JsParseError("Invalid left-hand side in assignment", line);
                }
                advance();
                JsNode right = assignment();
                if (!op.equals("=")) {
                    right = binary(op.substring(0, 1), (JsNode) target.deepCopy(), right, line);
                }
                return at(new JsVariables.Assign(target, right), line);
            }
        }
        return left;
    }

    private boolean isArrowAhead() {
        int i = 0;
        if (peek().is("async") && !peek(1).is("function")) {
            i = 1;
        }
        Token t = peek(i);
        if (t.kind() == Kind.IDENT && !RESERVED.contains(t.text())) {
            return peek(i + 1).is("=>");
        }
        if (t.is("(")) {
            int depth = 0;
            for (int j = pos + i; j < tokens.size(); j++) {
                Token u = tokens.get(j);
                if (u.is("(")) {
                    depth++;
                } else if (u.is(")")) {
                    depth--;
                    if (depth == 0) {
                        return j + 1 < tokens.size() && tokens.get(j + 1).is("=>");
                    }
                } else if (u.kind() == Kind.EOF) {
                    return false;
                }
            }
        }
        return false;
    }

    private JsNode arrow() {
        int line = peek().line();
        accept("async");
        List<String> params = new ArrayList<>();
        if (accept("(")) {
            while (!at(")")) {
                params.add(identifier());
                if (!accept(",")) {
                    break;
                }
            }
            expect(")");
        } else {
            params.add(identifier());
        }
        expect("=>");
        return function("", params, line, !at("{"));
    }

    private JsNode conditional() {
        int line = peek().line();
        JsNode cond = binaryExpression(1);
        if (accept("?")) {
            JsNode a = assignment();
            expect(":");
            JsNode b = assignment();
            return at(new JsOperators.Conditional(cond, a, b), line);
        }
        return cond;
    }

    private static int precedence(Token t) {
        if (t.kind() != Kind.PUNCT) {
            return -1;
        }
        return switch (t.text()) {
            case "||" -> 1;
            case "&&" -> 2;
            case "==", "!=", "===", "!==" -> 3;
            case "<", ">", "<=", ">=" -> 4;
            case "+", "-" -> 5;
            case "*", "/", "%" -> 6;
            default -> -1;
        };
    }

    private JsNode binaryExpression(int minPrecedence) {
        JsNode left = unary();
        while (true) {
            Token t = peek();
            int p = precedence(t);
            if (t.is("??") || t.is("**")) {
                throw error("'" + t.text() + "' is not supported by this JavaScript subset yet");
            }
            if (p < minPrecedence) {
                return left;
            }
            advance();
            JsNode right = binaryExpression(p + 1);
            left = binary(t.text(), left, right, t.line());
        }
    }

    private JsNode binary(String op, JsNode l, JsNode r, int line) {
        JsNode n = switch (op) {
            case "+" -> JsOperatorsFactory.AddNodeGen.create(l, r);
            case "-" -> JsOperatorsFactory.SubNodeGen.create(l, r);
            case "*" -> JsOperatorsFactory.MulNodeGen.create(l, r);
            case "/" -> JsOperatorsFactory.DivNodeGen.create(l, r);
            case "%" -> JsOperatorsFactory.ModNodeGen.create(l, r);
            case "<" -> JsOperatorsFactory.LessThanNodeGen.create(l, r);
            case "<=" -> JsOperatorsFactory.LessEqualNodeGen.create(l, r);
            case ">" -> JsOperatorsFactory.GreaterThanNodeGen.create(l, r);
            case ">=" -> JsOperatorsFactory.GreaterEqualNodeGen.create(l, r);
            case "===" -> JsOperatorsFactory.StrictEqualsNodeGen.create(l, r);
            case "!==" -> new JsOperators.Not(JsOperatorsFactory.StrictEqualsNodeGen.create(l, r));
            case "==" -> JsOperatorsFactory.LooseEqualsNodeGen.create(l, r);
            case "!=" -> new JsOperators.Not(JsOperatorsFactory.LooseEqualsNodeGen.create(l, r));
            case "&&" -> new JsOperators.Logical(l, r, true);
            case "||" -> new JsOperators.Logical(l, r, false);
            default -> throw new JsParseError("Unsupported operator " + op, line);
        };
        return at(n, line);
    }

    private JsNode unary() {
        Token t = peek();
        int line = t.line();
        if (t.kind() == Kind.PUNCT) {
            switch (t.text()) {
                case "!":
                    advance();
                    return at(new JsOperators.Not(unary()), line);
                case "-": {
                    advance();
                    if (peek().kind() == Kind.NUM) {
                        Object v = advance().value();
                        if (v instanceof Integer i && i != 0) {
                            return new JsOperators.Constant(-i);
                        }
                        return new JsOperators.Constant(-((Number) v).doubleValue());
                    }
                    return at(JsOperatorsFactory.NegNodeGen.create(unary()), line);
                }
                case "+":
                    advance();
                    return at(new JsOperators.ToNumber(unary()), line);
                case "++", "--": {
                    advance();
                    JsNode operand = unary();
                    if (!(operand instanceof JsVariables.Target target)) {
                        throw new JsParseError("Invalid left-hand side expression in prefix operation", line);
                    }
                    JsNode updated = binary(t.text().substring(0, 1), (JsNode) target.deepCopy(), new JsOperators.Constant(1), line);
                    return at(new JsVariables.Assign(target, updated), line);
                }
                default:
            }
        }
        if (t.is("await")) {
            advance();
            return at(new JsCalls.Await(unary()), line);
        }
        if (t.is("typeof") || t.is("delete") || t.is("void")) {
            throw error("'" + t.text() + "' is not supported by this JavaScript subset yet");
        }
        return postfix();
    }

    private JsNode postfix() {
        int line = peek().line();
        JsNode e = callOrMember();
        if ((at("++") || at("--")) && peek().line() == line) {
            String op = advance().text();
            if (!(e instanceof JsVariables.Target target)) {
                throw new JsParseError("Invalid left-hand side expression in postfix operation", line);
            }
            JsNode updated = binary(op.substring(0, 1), (JsNode) target.deepCopy(), new JsOperators.Constant(1), line);
            JsNode oldValue = (JsNode) target.deepCopy();
            return at(new JsVariables.Postfix(oldValue, at(new JsVariables.Assign(target, updated), line)), line);
        }
        return e;
    }

    private JsNode callOrMember() {
        JsNode e = primary();
        while (true) {
            int line = peek().line();
            if (accept(".")) {
                String name = advance().text();
                if (at("(")) {
                    e = at(new JsCalls.Call(null, e, name, arguments()), line);
                } else {
                    e = at(new JsVariables.Property(e, name), line);
                }
            } else if (at("(")) {
                e = at(new JsCalls.Call(e, null, null, arguments()), line);
            } else if (at("[") || at("?.")) {
                throw error("'" + peek().text() + "' is not supported by this JavaScript subset yet");
            } else {
                return e;
            }
        }
    }

    private JsNode[] arguments() {
        expect("(");
        List<JsNode> args = new ArrayList<>();
        while (!at(")")) {
            if (at("...")) {
                throw error("Spread arguments are not supported by this JavaScript subset yet");
            }
            args.add(assignment());
            if (!accept(",")) {
                break;
            }
        }
        expect(")");
        return args.toArray(new JsNode[0]);
    }

    private JsNode primary() {
        Token t = advance();
        int line = t.line();
        switch (t.kind()) {
            case NUM:
                return new JsOperators.Constant(t.value());
            case STR:
                return new JsOperators.Constant(t.value());
            case TEMPLATE:
                return template(t);
            case PUNCT:
                if (t.is("(")) {
                    JsNode e = expression();
                    expect(")");
                    return e;
                }
                if (t.is("{")) {
                    return objectLiteral(line);
                }
                if (t.is("[")) {
                    throw new JsParseError("Arrays are not supported by this JavaScript subset yet", line);
                }
                throw new JsParseError("Unexpected token '" + t.text() + "'", line);
            case IDENT:
                switch (t.text()) {
                    case "true":
                        return new JsOperators.Constant(true);
                    case "false":
                        return new JsOperators.Constant(false);
                    case "null":
                        return new JsOperators.Constant(Null.INSTANCE);
                    case "function": {
                        String name = peek().kind() == Kind.IDENT && !at("(") ? identifier() : "";
                        return functionRest(name, line);
                    }
                    case "async":
                        if (at("function")) {
                            advance();
                            String name = peek().kind() == Kind.IDENT && !at("(") ? identifier() : "";
                            return functionRest(name, line);
                        }
                        break;
                    default:
                }
                if (RESERVED.contains(t.text())) {
                    throw new JsParseError("'" + t.text() + "' is not supported here by this JavaScript subset", line);
                }
                return at(resolve(t.text(), line), line);
            default:
                throw new JsParseError("Unexpected end of input", line);
        }
    }

    private JsNode objectLiteral(int line) {
        List<String> keys = new ArrayList<>();
        List<JsNode> values = new ArrayList<>();
        while (!at("}")) {
            Token k = advance();
            String key = k.kind() == Kind.STR ? (String) k.value() : k.text();
            if (k.kind() == Kind.NUM) {
                key = dev.phpruntime.js.runtime.JsOps.toStr(k.value());
            }
            keys.add(key);
            if (accept(":")) {
                values.add(assignment());
            } else {
                values.add(at(resolve(key, k.line()), k.line()));
            }
            if (!accept(",")) {
                break;
            }
        }
        expect("}");
        return at(new JsCalls.ObjectLiteral(keys.toArray(new String[0]), values.toArray(new JsNode[0])), line);
    }

    private JsNode template(Token t) {
        List<JsNode> parts = new ArrayList<>();
        for (TemplatePart p : t.parts()) {
            if (p.expression() == null) {
                if (!p.text().isEmpty()) {
                    parts.add(new JsOperators.Constant(p.text()));
                }
                continue;
            }
            List<Token> saved = tokens;
            int savedPos = pos;
            tokens = new JsLexer(p.expression(), p.line()).lex();
            pos = 0;
            JsNode e = expression();
            if (peek().kind() != Kind.EOF) {
                throw error("Unexpected token '" + peek().text() + "' in template expression");
            }
            tokens = saved;
            pos = savedPos;
            parts.add(e);
        }
        return at(new JsCalls.Template(parts.toArray(new JsNode[0])), t.line());
    }

    /** Local slot of the current function, or a top-level binding in the module frame. */
    private JsVariables.Target resolve(String name, int line) {
        if (scope.slots.containsKey(name) || scope.module) {
            return scope.local(name);
        }
        for (Scope s = scope.parent; s != null && !s.module; s = s.parent) {
            if (s.slots.containsKey(name)) {
                throw new JsParseError("Closures over local variables are not supported yet ('" + name + "')", line);
            }
        }
        int slot = moduleScope.declare(name);
        return new JsVariables.Module(slot, name, moduleScope.kind(slot));
    }
}
