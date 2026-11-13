package dev.phpruntime.parser;

import com.oracle.truffle.api.source.Source;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.nodes.PhpMainRootNode;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.call.CallNode;
import dev.phpruntime.nodes.expr.*;
import dev.phpruntime.nodes.stmt.*;
import dev.phpruntime.nodes.var.*;
import dev.phpruntime.parser.Token.Kind;
import dev.phpruntime.runtime.PhpArith;
import dev.phpruntime.runtime.PhpFunction;
import dev.phpruntime.runtime.PhpNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Recursive-descent parser for statements with a Pratt (precedence climbing) parser
 * for expressions. It emits Truffle nodes directly instead of an intermediate AST,
 * the same approach as SimpleLanguage.
 */
public final class Parser {

    private static final Map<String, Object> CONSTANTS = Map.ofEntries(
            Map.entry("PHP_EOL", "\n"),
            Map.entry("PHP_INT_MAX", Long.MAX_VALUE),
            Map.entry("PHP_INT_MIN", Long.MIN_VALUE),
            Map.entry("PHP_INT_SIZE", 8L),
            Map.entry("PHP_FLOAT_EPSILON", Math.ulp(1.0)),
            Map.entry("PHP_FLOAT_MAX", Double.MAX_VALUE),
            Map.entry("PHP_VERSION", "8.3.0-truffle"),
            Map.entry("PHP_MAJOR_VERSION", 8L),
            Map.entry("PHP_OS", "Linux"),
            Map.entry("PHP_OS_FAMILY", "Linux"),
            Map.entry("M_PI", Math.PI),
            Map.entry("M_E", Math.E),
            Map.entry("NAN", Double.NaN),
            Map.entry("INF", Double.POSITIVE_INFINITY),
            Map.entry("E_ALL", 32767L),
            Map.entry("E_ERROR", 1L),
            Map.entry("E_WARNING", 2L),
            Map.entry("E_NOTICE", 8L),
            Map.entry("COUNT_RECURSIVE", 1L),
            Map.entry("COUNT_NORMAL", 0L),
            Map.entry("SORT_REGULAR", 0L),
            Map.entry("SORT_NUMERIC", 1L),
            Map.entry("SORT_STRING", 2L),
            Map.entry("STR_PAD_RIGHT", 1L),
            Map.entry("STR_PAD_LEFT", 0L),
            Map.entry("STR_PAD_BOTH", 2L),
            Map.entry("JSON_PRETTY_PRINT", 128L),
            Map.entry("JSON_UNESCAPED_SLASHES", 64L),
            Map.entry("JSON_UNESCAPED_UNICODE", 256L),
            Map.entry("JSON_THROW_ON_ERROR", 4194304L),
            Map.entry("ENT_QUOTES", 3L),
            Map.entry("PHP_ROUND_HALF_UP", 1L));

    private final PhpLanguage language;
    private final Source source;
    private final List<Token> tokens;
    private final List<PhpFunction> functions;
    private Scope scope;
    private int pos;

    public Parser(PhpLanguage language, Source source) {
        this(language, source, lex(source), new Scope(true), new ArrayList<>());
    }

    private static List<Token> lex(Source source) {
        try {
            return Lexer.tokenize(source.getCharacters().toString());
        } catch (PhpParseError e) {
            throw e.withSource(source);
        }
    }

    private Parser(PhpLanguage language, Source source, List<Token> tokens, Scope scope, List<PhpFunction> functions) {
        this.language = language;
        this.source = source;
        this.tokens = tokens;
        this.scope = scope;
        this.functions = functions;
    }

    public PhpMainRootNode parseMain() {
        try {
            List<PhpNode> stmts = new ArrayList<>();
            while (peek().kind() != Kind.EOF) {
                PhpNode s = statement();
                if (s != null) {
                    stmts.add(s);
                }
            }
            return new PhpMainRootNode(language, scope.build(), source, block(stmts), functions);
        } catch (PhpParseError e) {
            throw e.withSource(source);
        }
    }

    // =================================================================== tokens

    private Token peek() {
        return tokens.get(pos);
    }

    private Token peek(int ahead) {
        return tokens.get(Math.min(pos + ahead, tokens.size() - 1));
    }

    private Token next() {
        return tokens.get(pos++);
    }

    private boolean acceptOp(String op) {
        if (peek().isOp(op)) {
            pos++;
            return true;
        }
        return false;
    }

    private boolean acceptKeyword(String kw) {
        if (peek().isKeyword(kw)) {
            pos++;
            return true;
        }
        return false;
    }

    private Token expectOp(String op) {
        if (!peek().isOp(op)) {
            throw error("syntax error, unexpected " + describe(peek()) + ", expecting \"" + op + "\"");
        }
        return next();
    }

    private void expectKeyword(String kw) {
        if (!acceptKeyword(kw)) {
            throw error("syntax error, unexpected " + describe(peek()) + ", expecting \"" + kw + "\"");
        }
    }

    /** A statement ends with ';' or a closing tag; end of file is also accepted. */
    private void endStatement() {
        if (acceptOp(";")) {
            return;
        }
        Kind k = peek().kind();
        if (k == Kind.CLOSE_TAG || k == Kind.EOF) {
            return;
        }
        throw error("syntax error, unexpected " + describe(peek()) + ", expecting \";\"");
    }

    private PhpParseError error(String msg) {
        return new PhpParseError(msg, peek().line());
    }

    private static String describe(Token t) {
        return switch (t.kind()) {
            case EOF -> "end of file";
            case VARIABLE -> "variable \"$" + t.text() + "\"";
            case IDENT -> "identifier \"" + t.text() + "\"";
            case STRING, TEMPLATE -> "string content";
            default -> "token \"" + t.text() + "\"";
        };
    }

    // =================================================================== statements

    private PhpNode statement() {
        Token t = peek();
        int line = t.line();
        switch (t.kind()) {
            case INLINE_HTML -> {
                next();
                return new EchoNode(new PhpNode[]{new ConstNode(t.text())}).at(line);
            }
            case CLOSE_TAG -> {
                next();
                return null;
            }
            case OPEN_ECHO -> {
                next();
                PhpNode[] values = exprList();
                endStatement();
                return new EchoNode(values).at(line);
            }
            default -> {
            }
        }
        if (t.isOp("{")) {
            next();
            return blockUntil("}");
        }
        if (t.isOp(";")) {
            next();
            return null;
        }
        if (t.kind() == Kind.IDENT) {
            String kw = t.text().toLowerCase();
            switch (kw) {
                case "echo" -> {
                    next();
                    PhpNode[] values = exprList();
                    endStatement();
                    return new EchoNode(values).at(line);
                }
                case "if" -> {
                    next();
                    return ifStatement();
                }
                case "while" -> {
                    next();
                    return whileStatement();
                }
                case "do" -> {
                    next();
                    PhpNode body = statementOrEmpty();
                    expectKeyword("while");
                    expectOp("(");
                    PhpNode cond = expression();
                    expectOp(")");
                    endStatement();
                    return LoopStmtNode.doWhile(body, cond).at(line);
                }
                case "for" -> {
                    next();
                    return forStatement();
                }
                case "foreach" -> {
                    next();
                    return foreachStatement();
                }
                case "switch" -> {
                    next();
                    return switchStatement();
                }
                case "break", "continue" -> {
                    next();
                    int levels = 1;
                    if (peek().kind() == Kind.INT) {
                        levels = Integer.parseInt(next().text());
                    }
                    endStatement();
                    return new JumpNode(kw.equals("break") ? JumpNode.Kind.BREAK : JumpNode.Kind.CONTINUE, levels, null).at(line);
                }
                case "return" -> {
                    next();
                    PhpNode value = null;
                    if (!peek().isOp(";") && peek().kind() != Kind.CLOSE_TAG && peek().kind() != Kind.EOF) {
                        value = expression();
                    }
                    endStatement();
                    return new JumpNode(JumpNode.Kind.RETURN, 0, value).at(line);
                }
                case "function" -> {
                    if (peek(1).kind() == Kind.IDENT) {
                        next();
                        functionDeclaration();
                        return null;
                    }
                }
                case "global" -> {
                    next();
                    do {
                        Token v = next();
                        if (v.kind() != Kind.VARIABLE) {
                            throw error("expected variable after global");
                        }
                        scope.importGlobal(v.text());
                    } while (acceptOp(","));
                    endStatement();
                    return null;
                }
                case "unset" -> {
                    next();
                    expectOp("(");
                    List<LValueNode> targets = new ArrayList<>();
                    do {
                        if (peek().isOp(")")) {
                            break;
                        }
                        targets.add(lvalue(expression()));
                    } while (acceptOp(","));
                    expectOp(")");
                    endStatement();
                    return new UnsetNode(targets.toArray(new LValueNode[0])).at(line);
                }
                case "declare" -> {
                    // declare(strict_types=1); is accepted and ignored.
                    next();
                    expectOp("(");
                    while (!acceptOp(")")) {
                        next();
                    }
                    endStatement();
                    return null;
                }
                default -> {
                }
            }
        }
        PhpNode e = expression();
        endStatement();
        return e;
    }

    private PhpNode statementOrEmpty() {
        PhpNode s = statement();
        return s == null ? block(List.of()) : s;
    }

    private PhpNode blockUntil(String closing) {
        List<PhpNode> stmts = new ArrayList<>();
        while (!acceptOp(closing)) {
            if (peek().kind() == Kind.EOF) {
                throw error("syntax error, unexpected end of file");
            }
            PhpNode s = statement();
            if (s != null) {
                stmts.add(s);
            }
        }
        return block(stmts);
    }

    /** Statements for the alternative syntax ({@code if (...): ... endif;}). */
    private PhpNode blockUntilKeyword(String... terminators) {
        List<PhpNode> stmts = new ArrayList<>();
        while (true) {
            Token t = peek();
            if (t.kind() == Kind.EOF) {
                throw error("syntax error, unexpected end of file");
            }
            for (String term : terminators) {
                if (t.isKeyword(term)) {
                    return block(stmts);
                }
            }
            PhpNode s = statement();
            if (s != null) {
                stmts.add(s);
            }
        }
    }

    private static PhpNode block(List<PhpNode> stmts) {
        if (stmts.size() == 1) {
            return stmts.get(0);
        }
        return new BlockNode(stmts.toArray(new PhpNode[0]));
    }

    private PhpNode parenExpression() {
        expectOp("(");
        PhpNode e = expression();
        expectOp(")");
        return e;
    }

    private PhpNode ifStatement() {
        int line = peek().line();
        PhpNode cond = parenExpression();
        if (acceptOp(":")) {
            PhpNode then = blockUntilKeyword("elseif", "else", "endif");
            PhpNode otherwise = null;
            if (acceptKeyword("elseif")) {
                otherwise = ifStatement();
                return new IfNode(cond, then, otherwise).at(line);
            }
            if (acceptKeyword("else")) {
                if (peek().isKeyword("if")) {
                    next();
                    otherwise = ifStatement();
                    return new IfNode(cond, then, otherwise).at(line);
                }
                expectOp(":");
                otherwise = blockUntilKeyword("endif");
            }
            expectKeyword("endif");
            endStatement();
            return new IfNode(cond, then, otherwise).at(line);
        }
        PhpNode then = statementOrEmpty();
        PhpNode otherwise = null;
        if (acceptKeyword("elseif")) {
            otherwise = ifStatement();
        } else if (peek().isKeyword("else")) {
            next();
            if (acceptKeyword("if")) {
                otherwise = ifStatement();
            } else {
                otherwise = statementOrEmpty();
            }
        }
        return new IfNode(cond, then, otherwise).at(line);
    }

    private PhpNode whileStatement() {
        int line = peek().line();
        PhpNode cond = parenExpression();
        PhpNode body;
        if (acceptOp(":")) {
            body = blockUntilKeyword("endwhile");
            next();
            endStatement();
        } else {
            body = statementOrEmpty();
        }
        return LoopStmtNode.whileLoop(cond, body).at(line);
    }

    private PhpNode forStatement() {
        int line = peek().line();
        expectOp("(");
        PhpNode init = peek().isOp(";") ? null : block(List.of(exprList()));
        expectOp(";");
        PhpNode cond = null;
        if (!peek().isOp(";")) {
            PhpNode[] conds = exprList();
            // In "for(a; b, c; d)" the last expression decides.
            cond = conds.length == 1 ? conds[0] : new BlockValueNode(conds);
        }
        expectOp(";");
        PhpNode update = peek().isOp(")") ? null : block(List.of(exprList()));
        expectOp(")");
        PhpNode body;
        if (acceptOp(":")) {
            body = blockUntilKeyword("endfor");
            next();
            endStatement();
        } else {
            body = statementOrEmpty();
        }
        return LoopStmtNode.forLoop(init, cond, update, body).at(line);
    }

    private PhpNode foreachStatement() {
        int line = peek().line();
        expectOp("(");
        PhpNode subject = expression();
        expectKeyword("as");
        acceptOp("&"); // by-reference iteration is treated as by-value
        LValueNode first = lvalue(postfixOnly());
        LValueNode key = null;
        LValueNode value = first;
        if (acceptOp("=>")) {
            acceptOp("&");
            key = first;
            value = lvalue(postfixOnly());
        }
        expectOp(")");
        PhpNode body;
        if (acceptOp(":")) {
            body = blockUntilKeyword("endforeach");
            next();
            endStatement();
        } else {
            body = statementOrEmpty();
        }
        return new ForeachNode(subject, key, value, body).at(line);
    }

    private PhpNode switchStatement() {
        int line = peek().line();
        PhpNode subject = parenExpression();
        boolean alt = !peek().isOp("{");
        if (alt) {
            expectOp(":");
        } else {
            expectOp("{");
        }
        List<PhpNode> values = new ArrayList<>();
        List<PhpNode> bodies = new ArrayList<>();
        while (true) {
            if (!alt && acceptOp("}")) {
                break;
            }
            if (alt && acceptKeyword("endswitch")) {
                endStatement();
                break;
            }
            if (acceptKeyword("case")) {
                values.add(expression());
            } else if (acceptKeyword("default")) {
                values.add(null);
            } else {
                throw error("syntax error, unexpected " + describe(peek()) + " in switch");
            }
            if (!acceptOp(":")) {
                expectOp(";");
            }
            List<PhpNode> stmts = new ArrayList<>();
            while (!peek().isKeyword("case") && !peek().isKeyword("default") && !peek().isOp("}")
                    && !peek().isKeyword("endswitch") && peek().kind() != Kind.EOF) {
                PhpNode s = statement();
                if (s != null) {
                    stmts.add(s);
                }
            }
            bodies.add(block(stmts));
        }
        return new SwitchNode(subject, values.toArray(new PhpNode[0]), bodies.toArray(new PhpNode[0])).at(line);
    }

    private void functionDeclaration() {
        Token nameTok = next();
        String name = nameTok.text();
        Scope outer = scope;
        scope = new Scope(false);
        try {
            expectOp("(");
            List<Integer> slots = new ArrayList<>();
            List<PhpNode> defaults = new ArrayList<>();
            while (!acceptOp(")")) {
                skipType();
                acceptOp("&");
                acceptOp("...");
                Token v = next();
                if (v.kind() != Kind.VARIABLE) {
                    throw new PhpParseError("syntax error, expected parameter variable", v.line());
                }
                slots.add(scope.slot(v.text()));
                defaults.add(acceptOp("=") ? expression() : null);
                if (!peek().isOp(")")) {
                    expectOp(",");
                }
            }
            if (acceptOp(":")) {
                skipType();
            }
            expectOp("{");
            PhpNode body = blockUntil("}");
            int[] slotArray = slots.stream().mapToInt(Integer::intValue).toArray();
            VarKind[] kindArray = new VarKind[slotArray.length];
            for (int i = 0; i < slotArray.length; i++) {
                kindArray[i] = scope.kind(slotArray[i]);
            }
            PhpFunctionRootNode root = new PhpFunctionRootNode(language, scope.build(), source, nameTok.line(), name, slotArray,
                    kindArray, defaults.toArray(new PhpNode[0]), body);
            functions.add(new PhpFunction(name, root.getCallTarget()));
        } finally {
            scope = outer;
        }
    }

    /** Skips an optional type declaration such as {@code ?int}, {@code int|string} or {@code array}. */
    private void skipType() {
        acceptOp("?");
        while (peek().kind() == Kind.IDENT && (peek(1).kind() == Kind.VARIABLE || peek(1).isOp("|")
                || peek(1).isOp("&") || peek(1).isOp("...") || peek(1).isOp("{") || peek(1).isOp(")"))) {
            next();
            if (!acceptOp("|")) {
                break;
            }
        }
    }

    // =================================================================== expressions

    private PhpNode[] exprList() {
        List<PhpNode> list = new ArrayList<>();
        do {
            list.add(expression());
        } while (acceptOp(","));
        return list.toArray(new PhpNode[0]);
    }

    public PhpNode expression() {
        return binary(0);
    }

    /** Binding power of an infix operator; higher binds tighter. 0 means "not infix". */
    private static int infixPower(Token t) {
        if (t.kind() == Kind.IDENT) {
            return switch (t.text().toLowerCase()) {
                case "or" -> 1;
                case "xor" -> 2;
                case "and" -> 3;
                default -> 0;
            };
        }
        if (t.kind() != Kind.OP) {
            return 0;
        }
        return switch (t.text()) {
            case "?" -> 5;
            case "??" -> 7;
            case "||" -> 8;
            case "&&" -> 9;
            case "|" -> 10;
            case "^" -> 11;
            case "&" -> 12;
            case "==", "!=", "<>", "===", "!==", "<=>" -> 13;
            case "<", "<=", ">", ">=" -> 14;
            case "." -> 15;
            case "<<", ">>" -> 16;
            case "+", "-" -> 17;
            case "*", "/", "%" -> 18;
            case "**" -> 21;
            default -> 0;
        };
    }

    private static final int UNARY_POWER = 20;

    private PhpNode binary(int minPower) {
        PhpNode left = unary();
        while (true) {
            Token op = peek();
            int power = infixPower(op);
            if (power == 0 || power <= minPower) {
                return left;
            }
            next();
            int line = op.line();
            String text = op.kind() == Kind.IDENT ? op.text().toLowerCase() : op.text();
            if (text.equals("?")) {
                PhpNode whenTrue = peek().isOp(":") ? null : binary(0);
                expectOp(":");
                PhpNode whenFalse = binary(power);
                left = new TernaryNode(left, whenTrue, whenFalse).at(line);
                continue;
            }
            // ** and ?? are right-associative: parse the right side at one power lower.
            boolean rightAssoc = text.equals("**") || text.equals("??");
            PhpNode right = binary(rightAssoc ? power - 1 : power);
            left = makeBinary(text, left, right).at(line);
        }
    }

    private static PhpNode makeBinary(String op, PhpNode l, PhpNode r) {
        return switch (op) {
            case "+" -> AddNodeGen.create(l, r);
            case "-" -> SubNodeGen.create(l, r);
            case "*" -> MulNodeGen.create(l, r);
            case "/" -> DivNodeGen.create(l, r);
            case "%" -> ModNodeGen.create(l, r);
            case "." -> ConcatNodeGen.create(l, r);
            case "**" -> new ArithNode(PhpArith.Op.POW, l, r);
            case "&" -> new ArithNode(PhpArith.Op.BIT_AND, l, r);
            case "|" -> new ArithNode(PhpArith.Op.BIT_OR, l, r);
            case "^" -> new ArithNode(PhpArith.Op.BIT_XOR, l, r);
            case "<<" -> new ArithNode(PhpArith.Op.SHL, l, r);
            case ">>" -> new ArithNode(PhpArith.Op.SHR, l, r);
            case "<" -> CompareNodeGen.create(CompareNode.Kind.LT, l, r);
            case "<=" -> CompareNodeGen.create(CompareNode.Kind.LE, l, r);
            case ">" -> CompareNodeGen.create(CompareNode.Kind.GT, l, r);
            case ">=" -> CompareNodeGen.create(CompareNode.Kind.GE, l, r);
            case "<=>" -> CompareNodeGen.create(CompareNode.Kind.SPACESHIP, l, r);
            case "==" -> EqualNodeGen.create(false, l, r);
            case "!=", "<>" -> EqualNodeGen.create(true, l, r);
            case "===" -> IdenticalNodeGen.create(false, l, r);
            case "!==" -> IdenticalNodeGen.create(true, l, r);
            case "&&", "and" -> new AndNode(l, r);
            case "||", "or" -> new OrNode(l, r);
            case "xor" -> IdenticalNodeGen.create(true, new NotNode(new NotNode(l)), new NotNode(new NotNode(r)));
            case "??" -> new CoalesceNode(l, r);
            default -> throw new IllegalStateException(op);
        };
    }

    private PhpNode unary() {
        Token t = peek();
        int line = t.line();
        if (t.kind() == Kind.OP) {
            switch (t.text()) {
                case "!" -> {
                    next();
                    return new NotNode(binary(UNARY_POWER - 1)).at(line);
                }
                case "-" -> {
                    next();
                    PhpNode operand = binary(UNARY_POWER - 1);
                    if (operand instanceof ConstNode c && c.value() instanceof Long l && l != Long.MIN_VALUE) {
                        return new ConstNode(-l).at(line);
                    }
                    if (operand instanceof ConstNode c && c.value() instanceof Double d) {
                        return new ConstNode(-d).at(line);
                    }
                    return NegNodeGen.create(operand).at(line);
                }
                case "+" -> {
                    next();
                    return new UnaryNode(binary(UNARY_POWER - 1), false).at(line);
                }
                case "~" -> {
                    next();
                    return new UnaryNode(binary(UNARY_POWER - 1), true).at(line);
                }
                case "@" -> {
                    next();
                    return binary(UNARY_POWER - 1);
                }
                case "++", "--" -> {
                    next();
                    LValueNode target = lvalue(postfixOnly());
                    return new IncDecNode(target, t.text().equals("++"), true).at(line);
                }
                case "(" -> {
                    CastNode.Kind cast = castKind();
                    if (cast != null) {
                        pos += 3;
                        return new CastNode(cast, binary(UNARY_POWER - 1)).at(line);
                    }
                }
                default -> {
                }
            }
        }
        if (t.isKeyword("print")) {
            next();
            return new PrintNode(binary(4), false).at(line);
        }
        return postfix();
    }

    private CastNode.Kind castKind() {
        if (peek(1).kind() != Kind.IDENT || !peek(2).isOp(")")) {
            return null;
        }
        return switch (peek(1).text().toLowerCase()) {
            case "int", "integer" -> CastNode.Kind.INT;
            case "float", "double", "real" -> CastNode.Kind.FLOAT;
            case "string" -> CastNode.Kind.STRING;
            case "bool", "boolean" -> CastNode.Kind.BOOL;
            case "array" -> CastNode.Kind.ARRAY;
            default -> null;
        };
    }

    /** Primary plus postfix operators, then an optional assignment if the result is assignable. */
    private PhpNode postfix() {
        int line = peek().line();
        PhpNode e = postfixOnly();
        if (e instanceof LValueNode target) {
            Token t = peek();
            if (t.kind() == Kind.OP) {
                PhpArith.Op compound = compoundOp(t.text());
                if (t.text().equals("=")) {
                    next();
                    acceptOp("&"); // $a = &$b is treated as a value assignment
                    return new AssignNode(target, binary(4)).at(line);
                }
                if (compound != null) {
                    next();
                    PhpNode value = binary(4);
                    if (target instanceof LocalVarNode local && arithmetic(compound) != null) {
                        // $x += v is $x = $x + v for a plain variable, and that way it gets the
                        // typed operator nodes and an unboxed assignment.
                        return new AssignNode(target, makeBinary(arithmetic(compound), local.copyForRead(), value).at(line)).at(line);
                    }
                    return new CompoundAssignNode(target, compound, value).at(line);
                }
                if (t.text().equals("++") || t.text().equals("--")) {
                    next();
                    return new IncDecNode(target, t.text().equals("++"), false).at(line);
                }
            }
        }
        return e;
    }

    /** The binary operator for a compound assignment that has a typed operator node. */
    private static String arithmetic(PhpArith.Op op) {
        return switch (op) {
            case ADD -> "+";
            case SUB -> "-";
            case MUL -> "*";
            case DIV -> "/";
            case MOD -> "%";
            default -> null;
        };
    }

    private static PhpArith.Op compoundOp(String op) {
        return switch (op) {
            case "+=" -> PhpArith.Op.ADD;
            case "-=" -> PhpArith.Op.SUB;
            case "*=" -> PhpArith.Op.MUL;
            case "/=" -> PhpArith.Op.DIV;
            case "%=" -> PhpArith.Op.MOD;
            case "**=" -> PhpArith.Op.POW;
            case ".=" -> PhpArith.Op.CONCAT;
            case "&=" -> PhpArith.Op.BIT_AND;
            case "|=" -> PhpArith.Op.BIT_OR;
            case "^=" -> PhpArith.Op.BIT_XOR;
            case "<<=" -> PhpArith.Op.SHL;
            case ">>=" -> PhpArith.Op.SHR;
            case "??=" -> PhpArith.Op.COALESCE;
            default -> null;
        };
    }

    private PhpNode postfixOnly() {
        Token t = peek();
        int line = t.line();
        if (t.kind() == Kind.VARIABLE) {
            next();
            LValueNode var = scope.variable(t.text());
            var.at(line);
            List<PhpNode> keys = new ArrayList<>();
            while (peek().isOp("[")) {
                next();
                keys.add(peek().isOp("]") ? null : expression());
                expectOp("]");
            }
            if (keys.isEmpty()) {
                return var;
            }
            return new IndexLValueNode(var, keys.toArray(new PhpNode[0])).at(line);
        }
        PhpNode e = primary();
        while (peek().isOp("[")) {
            next();
            PhpNode key = expression();
            expectOp("]");
            e = new IndexReadNode(e, key).at(line);
        }
        return e;
    }

    private static LValueNode lvalueOrNull(PhpNode n) {
        return n instanceof LValueNode lv ? lv : null;
    }

    private LValueNode lvalue(PhpNode n) {
        if (n instanceof LValueNode lv) {
            return lv;
        }
        throw error("Cannot assign to this expression");
    }

    private PhpNode primary() {
        Token t = next();
        int line = t.line();
        switch (t.kind()) {
            case INT -> {
                try {
                    return new ConstNode(Long.parseLong(t.text())).at(line);
                } catch (NumberFormatException overflow) {
                    return new ConstNode(Double.parseDouble(t.text())).at(line);
                }
            }
            case FLOAT -> {
                return new ConstNode(Double.parseDouble(t.text())).at(line);
            }
            case STRING -> {
                return new ConstNode(t.text()).at(line);
            }
            case TEMPLATE -> {
                return interpolation(t).at(line);
            }
            case IDENT -> {
                return identifier(t);
            }
            case OP -> {
                if (t.text().equals("(")) {
                    PhpNode e = expression();
                    expectOp(")");
                    return e;
                }
                if (t.text().equals("[")) {
                    return arrayLiteral("]").at(line);
                }
            }
            default -> {
            }
        }
        pos--;
        throw error("syntax error, unexpected " + describe(t));
    }

    private PhpNode identifier(Token t) {
        int line = t.line();
        String name = t.text();
        String lower = name.toLowerCase();
        switch (lower) {
            case "true" -> {
                return new ConstNode(true).at(line);
            }
            case "false" -> {
                return new ConstNode(false).at(line);
            }
            case "null" -> {
                return new ConstNode(PhpNull.INSTANCE).at(line);
            }
            case "array" -> {
                if (peek().isOp("(")) {
                    next();
                    return arrayLiteral(")").at(line);
                }
            }
            case "isset" -> {
                expectOp("(");
                List<LValueNode> targets = new ArrayList<>();
                do {
                    targets.add(lvalue(expression()));
                } while (acceptOp(","));
                expectOp(")");
                return new IssetNode(targets.toArray(new LValueNode[0])).at(line);
            }
            case "empty" -> {
                expectOp("(");
                PhpNode e = expression();
                expectOp(")");
                return new NotNode(new CoalesceNode(e, new ConstNode(false))).at(line);
            }
            case "exit", "die" -> {
                PhpNode value = null;
                if (acceptOp("(")) {
                    if (!peek().isOp(")")) {
                        value = expression();
                    }
                    expectOp(")");
                }
                return new PrintNode(value, true).at(line);
            }
            default -> {
            }
        }
        if (peek().isOp("(")) {
            next();
            List<PhpNode> args = new ArrayList<>();
            while (!acceptOp(")")) {
                args.add(expression());
                if (!peek().isOp(")")) {
                    expectOp(",");
                }
            }
            PhpNode[] argArray = args.toArray(new PhpNode[0]);
            LValueNode[] refs = new LValueNode[argArray.length];
            for (int i = 0; i < argArray.length; i++) {
                refs[i] = lvalueOrNull(argArray[i]);
            }
            return new CallNode(name, argArray, refs).at(line);
        }
        Object constant = CONSTANTS.get(name);
        if (constant == null) {
            constant = CONSTANTS.get(name.toUpperCase());
        }
        if (constant != null) {
            return new ConstNode(constant).at(line);
        }
        throw new PhpParseError("Undefined constant \"" + name + "\"", line);
    }

    private PhpNode arrayLiteral(String closing) {
        List<PhpNode> keys = new ArrayList<>();
        List<PhpNode> values = new ArrayList<>();
        while (!acceptOp(closing)) {
            PhpNode first = expression();
            if (acceptOp("=>")) {
                keys.add(first);
                values.add(expression());
            } else {
                keys.add(null);
                values.add(first);
            }
            if (!peek().isOp(closing)) {
                expectOp(",");
            }
        }
        return new ArrayLiteralNode(keys.toArray(new PhpNode[0]), values.toArray(new PhpNode[0]));
    }

    private PhpNode interpolation(Token t) {
        List<PhpNode> parts = new ArrayList<>();
        for (Token.Part part : t.parts()) {
            if (!part.isExpr()) {
                parts.add(new ConstNode(part.value()));
                continue;
            }
            List<Token> sub = Lexer.tokenizeCode(part.value());
            Parser p = new Parser(language, source, sub, scope, functions);
            PhpNode e = p.expression();
            if (p.peek().kind() != Kind.EOF) {
                throw new PhpParseError("syntax error in string interpolation", t.line());
            }
            parts.add(e);
        }
        return new InterpolateNode(parts.toArray(new PhpNode[0]));
    }
}
