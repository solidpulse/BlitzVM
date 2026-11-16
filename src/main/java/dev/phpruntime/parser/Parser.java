package dev.phpruntime.parser;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.source.Source;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.PhpExprRootNode;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.nodes.PhpMainRootNode;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.call.CallNode;
import dev.phpruntime.nodes.call.CallValueNode;
import dev.phpruntime.nodes.call.MethodCallNode;
import dev.phpruntime.nodes.call.StaticMethodCallNode;
import dev.phpruntime.nodes.expr.*;
import dev.phpruntime.nodes.stmt.*;
import dev.phpruntime.nodes.var.*;
import dev.phpruntime.parser.Token.Kind;
import dev.phpruntime.runtime.PhpArith;
import dev.phpruntime.runtime.PhpClassTemplate;
import dev.phpruntime.runtime.PhpConstants;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpFunction;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.Visibility;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recursive-descent parser for statements with a Pratt (precedence climbing) parser
 * for expressions. It emits Truffle nodes directly instead of an intermediate AST,
 * the same approach as SimpleLanguage.
 */
public final class Parser {

    private final PhpLanguage language;
    private final Source source;
    private final List<Token> tokens;
    private final List<PhpFunction> functions;
    private final List<PhpClassTemplate> topLevelClasses;
    private Scope scope;
    private String currentFunction;
    private String currentClass;
    private boolean currentReturnsRef;
    private int hoistedClassesSlot = -1;
    private int pos;

    public Parser(PhpLanguage language, Source source) {
        this(language, source, lex(source), new Scope(language, true), new ArrayList<>(), new ArrayList<>(), "", null);
    }

    private static List<Token> lex(Source source) {
        try {
            return Lexer.tokenize(source.getCharacters().toString());
        } catch (PhpParseError e) {
            throw e.withSource(source);
        }
    }

    private Parser(PhpLanguage language, Source source, List<Token> tokens, Scope scope,
                   List<PhpFunction> functions, List<PhpClassTemplate> topLevelClasses,
                   String currentFunction, String currentClass) {
        this.language = language;
        this.source = source;
        this.tokens = tokens;
        this.scope = scope;
        this.functions = functions;
        this.topLevelClasses = topLevelClasses;
        this.currentFunction = currentFunction;
        this.currentClass = currentClass;
    }

    public PhpMainRootNode parseMain() {
        try {
            List<PhpNode> stmts = new ArrayList<>();
            while (peek().kind() != Kind.EOF) {
                PhpNode s = statement(true);
                if (s != null) {
                    stmts.add(s);
                }
            }
            return new PhpMainRootNode(language, scope.build(), source, block(stmts),
                    functions, topLevelClasses, hoistedClassesSlot);
        } catch (PhpParseError e) {
            throw e.withSource(source);
        }
    }

    private int hoistedClassesSlot() {
        if (hoistedClassesSlot < 0) {
            hoistedClassesSlot = scope.slot("@hoistedClasses");
        }
        return hoistedClassesSlot;
    }

    private String currentClassLower() {
        return currentClass == null ? null : currentClass.toLowerCase();
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

    private Token expectIdent() {
        Token t = peek();
        if (t.kind() != Kind.IDENT) {
            throw error("syntax error, unexpected " + describe(t) + ", expecting identifier");
        }
        return next();
    }

    private void skipAttributes() {
        while (acceptOp("#[")) {
            int depth = 1;
            while (depth > 0) {
                Token tok = next();
                if (tok.kind() == Kind.EOF) {
                    throw error("syntax error, unexpected end of file in attribute");
                }
                if (tok.isOp("[") || tok.isOp("#[")) {
                    depth++;
                } else if (tok.isOp("]")) {
                    depth--;
                }
            }
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
        return statement(false);
    }

    private PhpNode statement(boolean topLevel) {
        skipAttributes();
        Token t = peek();
        if (t.kind() == Kind.EOF) {
            return null;
        }
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
                    if (currentReturnsRef && value instanceof LocalVarNode lv && !"this".equals(lv.varName())) {
                        lv.kind().markCell();
                    }
                    return new JumpNode(JumpNode.Kind.RETURN, 0, value, currentReturnsRef).at(line);
                }
                case "function" -> {
                    if (peek(1).kind() == Kind.IDENT || (peek(1).isOp("&") && peek(2).kind() == Kind.IDENT)) {
                        next();
                        return functionDeclaration(topLevel, line);
                    }
                }
                case "const" -> {
                    if (!scope.isGlobal()) {
                        throw error("const declarations are only allowed at top level");
                    }
                    next();
                    return constStatement(line);
                }
                case "global" -> {
                    next();
                    List<Integer> slots = new ArrayList<>();
                    List<Integer> globalIds = new ArrayList<>();
                    do {
                        Token v = next();
                        if (v.kind() != Kind.VARIABLE) {
                            throw error("expected variable after global");
                        }
                        String name = v.text();
                        if ("GLOBALS".equals(name)) {
                            throw error("Cannot use $GLOBALS as global variable");
                        }
                        if (!scope.isGlobal()) {
                            scope.markCell(name);
                            slots.add(scope.slot(name));
                            globalIds.add(language.globalId(name));
                        }
                    } while (acceptOp(","));
                    endStatement();
                    if (slots.isEmpty()) {
                        return null;
                    }
                    return new GlobalImportNode(
                            slots.stream().mapToInt(Integer::intValue).toArray(),
                            globalIds.stream().mapToInt(Integer::intValue).toArray()).at(line);
                }
                case "static" -> {
                    if (peek(1).kind() == Kind.VARIABLE) {
                        next();
                        return staticStatement(line);
                    }
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
                case "abstract", "final", "class", "interface" -> {
                    return classDeclaration(topLevel, line);
                }
                case "try" -> {
                    next();
                    return tryStatement(line);
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
        boolean byRef = acceptOp("&");
        LValueNode first = lvalue(postfixOnly());
        LValueNode key = null;
        LValueNode value = first;
        if (acceptOp("=>")) {
            if (byRef) {
                throw error("Key element cannot be a reference");
            }
            byRef = acceptOp("&");
            key = first;
            value = lvalue(postfixOnly());
        }
        if (byRef && value instanceof LocalVarNode lv) {
            lv.kind().markCell();
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
        return new ForeachNode(subject, lvalueOrNull(subject), key, value, byRef, body).at(line);
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

    private PhpNode staticStatement(int line) {
        List<String> names = new ArrayList<>();
        List<Integer> slots = new ArrayList<>();
        List<Integer> globalIds = new ArrayList<>();
        List<PhpNode> inits = new ArrayList<>();
        do {
            Token v = next();
            if (v.kind() != Kind.VARIABLE) {
                throw error("expected variable after static");
            }
            String name = v.text();
            names.add(name);
            if (!scope.isGlobal()) {
                scope.markCell(name);
                slots.add(scope.slot(name));
                globalIds.add(-1);
            } else {
                slots.add(-1);
                globalIds.add(language.globalId(name));
            }
            inits.add(acceptOp("=") ? expression() : null);
        } while (acceptOp(","));
        endStatement();
        return new StaticDeclNode(
                scope.isGlobal(), scope.closureObjSlot(),
                names.toArray(new String[0]),
                slots.stream().mapToInt(Integer::intValue).toArray(),
                globalIds.stream().mapToInt(Integer::intValue).toArray(),
                inits.toArray(new PhpNode[0])).at(line);
    }

    private PhpNode tryStatement(int line) {
        expectOp("{");
        PhpNode tryBlock = blockUntil("}");
        List<TryCatchNode.CatchClause> catches = new ArrayList<>();
        while (acceptKeyword("catch")) {
            expectOp("(");
            List<Integer> ids = new ArrayList<>();
            List<Boolean> matchAll = new ArrayList<>();
            do {
                String typeName = expectIdent().text();
                String lower = typeName.toLowerCase();
                ids.add(language.classId(lower));
                matchAll.add("throwable".equals(lower));
            } while (acceptOp("|"));
            LValueNode varTarget = null;
            if (peek().kind() == Kind.VARIABLE) {
                Token vt = next();
                varTarget = scope.variable(vt.text());
                varTarget.at(vt.line());
            }
            expectOp(")");
            expectOp("{");
            PhpNode catchBody = blockUntil("}");
            int[] idArr = ids.stream().mapToInt(Integer::intValue).toArray();
            boolean[] allArr = new boolean[matchAll.size()];
            for (int i = 0; i < matchAll.size(); i++) {
                allArr[i] = matchAll.get(i);
            }
            catches.add(new TryCatchNode.CatchClause(idArr, allArr, varTarget, catchBody));
        }
        PhpNode finallyBlock = null;
        if (acceptKeyword("finally")) {
            expectOp("{");
            finallyBlock = blockUntil("}");
        }
        if (catches.isEmpty() && finallyBlock == null) {
            throw error("Cannot use try without catch or finally");
        }
        return new TryCatchNode(tryBlock, catches.toArray(new TryCatchNode.CatchClause[0]), finallyBlock).at(line);
    }

    private PhpNode constStatement(int line) {
        List<String> names = new ArrayList<>();
        List<PhpNode> values = new ArrayList<>();
        do {
            Token nameTok = next();
            if (nameTok.kind() != Kind.IDENT) {
                throw new PhpParseError("syntax error, unexpected " + describe(nameTok) + ", expecting identifier", nameTok.line());
            }
            expectOp("=");
            names.add(nameTok.text());
            values.add(expression());
        } while (acceptOp(","));
        endStatement();
        return new DefineConstantNode(names.toArray(new String[0]), values.toArray(new PhpNode[0])).at(line);
    }

    private record ParsedParams(String[] names, int[] slots, VarKind[] kinds, boolean[] byRef, PhpNode[] defaults, boolean variadic) {}

    private ParsedParams parseParams() {
        expectOp("(");
        List<String> names = new ArrayList<>();
        List<Integer> slots = new ArrayList<>();
        List<Boolean> byRef = new ArrayList<>();
        List<PhpNode> defaults = new ArrayList<>();
        boolean variadic = false;
        while (!acceptOp(")")) {
            skipAttributes();
            skipType();
            boolean isRef = acceptOp("&");
            boolean isVarArg = acceptOp("...");
            if (isVarArg) {
                variadic = true;
            }
            Token v = next();
            if (v.kind() != Kind.VARIABLE) {
                throw new PhpParseError("syntax error, expected parameter variable", v.line());
            }
            String pName = v.text();
            scope.declareParam(pName);
            if (isRef && !isVarArg) {
                scope.markCell(pName);
            }
            names.add(pName);
            slots.add(scope.slot(pName));
            byRef.add(isRef);
            defaults.add(acceptOp("=") ? expression() : null);
            if (!peek().isOp(")")) {
                expectOp(",");
            }
        }
        int[] slotArray = slots.stream().mapToInt(Integer::intValue).toArray();
        VarKind[] kindArray = new VarKind[slotArray.length];
        boolean[] refArray = new boolean[byRef.size()];
        for (int i = 0; i < slotArray.length; i++) {
            kindArray[i] = scope.kind(slotArray[i]);
            refArray[i] = byRef.get(i);
        }
        return new ParsedParams(names.toArray(new String[0]), slotArray, kindArray, refArray, defaults.toArray(new PhpNode[0]), variadic);
    }

    private PhpNode functionDeclaration(boolean topLevel, int line) {
        boolean returnsRef = acceptOp("&");
        Token nameTok = next();
        String name = nameTok.text();
        Scope outer = scope;
        String outerFunction = currentFunction;
        String outerClass = currentClass;
        boolean outerReturnsRef = currentReturnsRef;
        scope = new Scope(language, false);
        currentFunction = name;
        currentClass = null;
        currentReturnsRef = returnsRef;
        try {
            ParsedParams params = parseParams();
            if (acceptOp(":")) {
                skipType();
            }
            expectOp("{");
            PhpNode body = blockUntil("}");
            PhpFunctionRootNode root = new PhpFunctionRootNode(
                    language, scope.build(), source, nameTok.line(),
                    null, name, false, returnsRef, -1, -1, -1,
                    new int[0], new VarKind[0], new boolean[0],
                    params.names(), params.slots(), params.kinds(), params.byRef(),
                    params.defaults(), params.variadic(), body);
            PhpFunction fn = new PhpFunction(name, root.getCallTarget());
            if (topLevel) {
                functions.add(fn);
                return null;
            }
            return new DeclareFunctionNode(fn).at(line);
        } finally {
            scope = outer;
            currentFunction = outerFunction;
            currentClass = outerClass;
            currentReturnsRef = outerReturnsRef;
        }
    }

    private PhpNode parseClosure(int line, boolean isStatic) {
        boolean returnsRef = acceptOp("&");
        Scope outer = scope;
        String outerFunction = currentFunction;
        boolean outerReturnsRef = currentReturnsRef;
        scope = Scope.forClosure(language, outer, !isStatic, false);
        currentFunction = "{closure}";
        currentReturnsRef = returnsRef;
        try {
            ParsedParams params = parseParams();
            if (acceptKeyword("use")) {
                expectOp("(");
                while (!acceptOp(")")) {
                    boolean byRef = acceptOp("&");
                    Token v = next();
                    if (v.kind() != Kind.VARIABLE) {
                        throw error("syntax error, expected variable in use(...)");
                    }
                    scope.addExplicitCapture(v.text(), byRef, v.line());
                    if (!peek().isOp(")")) {
                        expectOp(",");
                    }
                }
            }
            if (acceptOp(":")) {
                skipType();
            }
            expectOp("{");
            PhpNode body = blockUntil("}");
            return finishClosure(outer, !isStatic, returnsRef, params, body, line, "{closure}");
        } finally {
            scope = outer;
            currentFunction = outerFunction;
            currentReturnsRef = outerReturnsRef;
        }
    }

    private PhpNode parseArrowFunction(int line, boolean isStatic) {
        boolean returnsRef = acceptOp("&");
        Scope outer = scope;
        String outerFunction = currentFunction;
        boolean outerReturnsRef = currentReturnsRef;
        scope = Scope.forClosure(language, outer, !isStatic, true);
        currentFunction = "{closure}";
        currentReturnsRef = returnsRef;
        try {
            ParsedParams params = parseParams();
            if (acceptOp(":")) {
                skipType();
            }
            expectOp("=>");
            PhpNode expr = expression();
            if (returnsRef && expr instanceof LocalVarNode lv && !"this".equals(lv.varName())) {
                lv.kind().markCell();
            }
            PhpNode body = new JumpNode(JumpNode.Kind.RETURN, 0, expr, returnsRef).at(line);
            return finishClosure(outer, !isStatic, returnsRef, params, body, line, "{closure}");
        } finally {
            scope = outer;
            currentFunction = outerFunction;
            currentReturnsRef = outerReturnsRef;
        }
    }

    private PhpNode finishClosure(Scope outer, boolean bindThis, boolean returnsRef, ParsedParams params, PhpNode body, int line, String fnName) {
        List<Scope.Capture> caps = scope.captures();
        int[] captureSlots = new int[caps.size()];
        VarKind[] captureKinds = new VarKind[caps.size()];
        LValueNode[] outerReads = new LValueNode[caps.size()];
        boolean[] warn = new boolean[caps.size()];
        boolean[] byRef = new boolean[caps.size()];
        for (int i = 0; i < caps.size(); i++) {
            Scope.Capture c = caps.get(i);
            captureSlots[i] = c.targetSlot();
            captureKinds[i] = c.targetKind();
            outerReads[i] = c.outerRead();
            warn[i] = c.warnIfUndefined();
            byRef[i] = c.byRef();
        }
        PhpFunctionRootNode root = new PhpFunctionRootNode(
                language, scope.build(), source, line,
                currentClass, fnName, !bindThis, returnsRef, scope.thisSlot(), scope.calledClassSlot(), scope.closureObjSlot(),
                captureSlots, captureKinds, byRef,
                params.names(), params.slots(), params.kinds(), params.byRef(),
                params.defaults(), params.variadic(), body);
        return new ClosureNode(root.getCallTarget(), bindThis,
                outer.thisSlot(), outer.calledClassSlot(), outerReads, warn, byRef).at(line);
    }

    private PhpNode classDeclaration(boolean topLevel, int line) {
        boolean isAbstract = false;
        boolean isFinal = false;
        boolean mods = true;
        while (mods && peek().kind() == Kind.IDENT) {
            switch (peek().text().toLowerCase()) {
                case "abstract" -> { next(); isAbstract = true; }
                case "final" -> { next(); isFinal = true; }
                case "readonly" -> next();
                default -> mods = false;
            }
        }
        boolean isInterface = acceptKeyword("interface");
        if (!isInterface) {
            expectKeyword("class");
        }
        String name = expectIdent().text();
        PhpClassTemplate template = parseClassTemplate(
                language.nextTemplateId(), name, isInterface, isAbstract, isFinal, false);
        if (topLevel) {
            int idx = topLevelClasses.size();
            topLevelClasses.add(template);
            return new DeclareClassNode(template, hoistedClassesSlot(), idx).at(line);
        }
        return new DeclareClassNode(template, -1, -1).at(line);
    }

    private PhpClassTemplate parseClassTemplate(long templateId, String name, boolean isInterface,
                                                  boolean isAbstract, boolean isFinal, boolean isAnonymous) {
        String parentName = null;
        List<String> interfaceNames = new ArrayList<>();
        if (acceptKeyword("extends")) {
            if (isInterface) {
                do {
                    interfaceNames.add(expectIdent().text());
                } while (acceptOp(","));
            } else {
                parentName = expectIdent().text();
            }
        }
        if (!isInterface && acceptKeyword("implements")) {
            do {
                interfaceNames.add(expectIdent().text());
            } while (acceptOp(","));
        }
        expectOp("{");
        List<PhpClassTemplate.PropertyDecl> properties = new ArrayList<>();
        List<PhpClassTemplate.ConstantDecl> constants = new ArrayList<>();
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        String outerClass = currentClass;
        currentClass = name;
        try {
            while (!acceptOp("}")) {
                if (peek().kind() == Kind.EOF) {
                    throw error("syntax error, unexpected end of file in class " + name);
                }
                skipAttributes();
                if (acceptOp("}")) {
                    break;
                }
                Visibility vis = Visibility.PUBLIC;
                boolean isStatic = false;
                boolean mAbstract = false;
                boolean mFinal = false;
                boolean parsingMods = true;
                while (parsingMods && peek().kind() == Kind.IDENT) {
                    switch (peek().text().toLowerCase()) {
                        case "public", "var" -> { next(); vis = Visibility.PUBLIC; }
                        case "protected" -> { next(); vis = Visibility.PROTECTED; }
                        case "private" -> { next(); vis = Visibility.PRIVATE; }
                        case "static" -> { next(); isStatic = true; }
                        case "abstract" -> { next(); mAbstract = true; }
                        case "final" -> { next(); mFinal = true; }
                        case "readonly" -> next();
                        default -> parsingMods = false;
                    }
                }
                if (acceptKeyword("const")) {
                    do {
                        String constName = expectIdent().text();
                        expectOp("=");
                        PhpNode expr = expression();
                        Object constVal = expr instanceof ConstNode c ? c.value() : null;
                        RootCallTarget valTarget = expr instanceof ConstNode ? null
                                : new PhpExprRootNode(language, source, expr).getCallTarget();
                        constants.add(new PhpClassTemplate.ConstantDecl(constName, vis, mFinal, constVal, valTarget));
                    } while (acceptOp(","));
                    expectOp(";");
                } else if (acceptKeyword("function")) {
                    PhpClassTemplate.MethodDecl m = parseMethod(vis, isStatic, isInterface || mAbstract, mFinal);
                    methods.put(m.lowerName(), m);
                } else {
                    skipType();
                    do {
                        Token propTok = next();
                        if (propTok.kind() != Kind.VARIABLE) {
                            throw new PhpParseError("syntax error, expected property variable", propTok.line());
                        }
                        PhpNode expr = acceptOp("=") ? expression() : null;
                        Object constVal = expr == null ? PhpNull.INSTANCE
                                : (expr instanceof ConstNode c ? c.value() : null);
                        RootCallTarget valTarget = expr == null || expr instanceof ConstNode ? null
                                : new PhpExprRootNode(language, source, expr).getCallTarget();
                        properties.add(new PhpClassTemplate.PropertyDecl(propTok.text(), vis, isStatic, constVal, valTarget));
                    } while (acceptOp(","));
                    expectOp(";");
                }
            }
        } finally {
            currentClass = outerClass;
        }
        return new PhpClassTemplate(
                templateId, name, isInterface, isAbstract, isFinal, isAnonymous,
                parentName, interfaceNames, properties, constants, methods);
    }

    private PhpClassTemplate.MethodDecl parseMethod(Visibility vis, boolean isStatic, boolean isAbstract, boolean isFinal) {
        boolean returnsRef = acceptOp("&");
        Token nameTok = expectIdent();
        String mName = nameTok.text();
        String lower = mName.toLowerCase();
        Scope outerScope = scope;
        String outerFunction = currentFunction;
        boolean outerReturnsRef = currentReturnsRef;
        scope = Scope.forMethod(language);
        currentFunction = mName;
        currentReturnsRef = returnsRef;
        try {
            ParsedParams params = parseParams();
            if (acceptOp(":")) {
                skipType();
            }
            RootCallTarget callTarget;
            if (isAbstract) {
                expectOp(";");
                callTarget = null;
            } else {
                expectOp("{");
                PhpNode body = blockUntil("}");
                PhpFunctionRootNode root = new PhpFunctionRootNode(
                        language, scope.build(), source, nameTok.line(),
                        currentClass, mName, isStatic, returnsRef, scope.thisSlot(), scope.calledClassSlot(), -1,
                        new int[0], new VarKind[0], new boolean[0],
                        params.names(), params.slots(), params.kinds(), params.byRef(),
                        params.defaults(), params.variadic(), body);
                callTarget = root.getCallTarget();
            }
            return new PhpClassTemplate.MethodDecl(
                    mName, lower, language.methodId(mName), vis, isStatic, isAbstract, isFinal, callTarget);
        } finally {
            scope = outerScope;
            currentFunction = outerFunction;
            currentReturnsRef = outerReturnsRef;
        }
    }

    /** Skips an optional type declaration such as {@code ?int}, {@code int|string} or {@code array}. */
    private void skipType() {
        acceptOp("?");
        while (peek().kind() == Kind.IDENT && (peek(1).kind() == Kind.VARIABLE || peek(1).isOp("|")
                || peek(1).isOp("&") || peek(1).isOp("...") || peek(1).isOp("{") || peek(1).isOp(")")
                || peek(1).isOp(";"))) {
            next();
            if (!acceptOp("|")) {
                break;
            }
            acceptOp("?");
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
                case "instanceof" -> 20;
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
            if (text.equals("instanceof")) {
                ClassRefNode rightRef = parseClassRef(true);
                left = new InstanceofNode(left, rightRef).at(line);
                continue;
            }
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
        if (t.kind() == Kind.IDENT) {
            String kw = t.text().toLowerCase();
            if (kw.equals("print")) {
                next();
                return new PrintNode(binary(4), false).at(line);
            }
            if (kw.equals("clone")) {
                next();
                return new CloneNode(binary(UNARY_POWER)).at(line);
            }
            if (kw.equals("throw")) {
                next();
                return new ThrowNode(expression()).at(line);
            }
            IncludeNode.Kind inc = includeKind(kw);
            if (inc != null) {
                next();
                return new IncludeNode(inc, expression()).at(line);
            }
        }
        return postfix();
    }

    private static IncludeNode.Kind includeKind(String kw) {
        return switch (kw) {
            case "include" -> IncludeNode.Kind.INCLUDE;
            case "include_once" -> IncludeNode.Kind.INCLUDE_ONCE;
            case "require" -> IncludeNode.Kind.REQUIRE;
            case "require_once" -> IncludeNode.Kind.REQUIRE_ONCE;
            default -> null;
        };
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
            case "object" -> CastNode.Kind.OBJECT;
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
                    if (acceptOp("&")) {
                        PhpNode rhs = binary(4);
                        if (target instanceof LocalVarNode lvTarget && !"this".equals(lvTarget.varName())) {
                            lvTarget.kind().markCell();
                        }
                        if (rhs instanceof LocalVarNode lvRhs && !"this".equals(lvRhs.varName())) {
                            lvRhs.kind().markCell();
                        }
                        return new AssignRefNode(target, rhs).at(line);
                    }
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
        return postfixOnly(true);
    }

    private PhpNode postfixOnly(boolean allowCall) {
        Token t = peek();
        int line = t.line();
        PhpNode e;
        if (t.kind() == Kind.VARIABLE) {
            next();
            LValueNode var = scope.variable(t.text());
            var.at(line);
            e = var;
        } else {
            e = primary();
        }
        boolean nullsafeChain = false;
        while (true) {
            if (acceptOp("[")) {
                if (e instanceof LValueNode lv) {
                    List<PhpNode> keys = new ArrayList<>();
                    keys.add(peek().isOp("]") ? null : expression());
                    expectOp("]");
                    while (peek().isOp("[")) {
                        next();
                        keys.add(peek().isOp("]") ? null : expression());
                        expectOp("]");
                    }
                    if (lv instanceof GlobalVarNode gv && gv.isGlobalsSuperglobal()) {
                        PhpNode firstKey = keys.get(0);
                        String constName = null;
                        int constId = -1;
                        if (firstKey instanceof ConstNode cn && cn.value() instanceof String s) {
                            Object norm = dev.phpruntime.runtime.PhpArray.normalizeKey(s);
                            constName = norm instanceof String ns ? ns : String.valueOf(norm);
                            constId = "GLOBALS".equals(constName) ? -1 : language.globalId(constName);
                        } else if (firstKey instanceof ConstNode cn && cn.value() instanceof Long l) {
                            constName = Long.toString(l);
                            constId = language.globalId(constName);
                        }
                        LValueNode elem = new GlobalsElementLValueNode(firstKey, constName, constId).at(line);
                        if (keys.size() == 1) {
                            e = elem;
                        } else {
                            e = new IndexLValueNode(elem, keys.subList(1, keys.size()).toArray(new PhpNode[0])).at(line);
                        }
                    } else {
                        e = new IndexLValueNode(lv, keys.toArray(new PhpNode[0])).at(line);
                    }
                } else {
                    PhpNode key = expression();
                    expectOp("]");
                    e = new IndexReadNode(e, key).at(line);
                }
            } else if (acceptOp("?->")) {
                nullsafeChain = true;
                e = parseMemberAccess(e, line, true);
            } else if (acceptOp("->")) {
                e = parseMemberAccess(e, line, nullsafeChain);
            } else if (acceptOp("::")) {
                e = parseStaticMember(ClassRefNode.dynamic(e), line);
            } else if (allowCall && acceptOp("(")) {
                if (consumeFirstClassCallableEllipsis()) {
                    e = FirstClassCallableNode.forCallable(e, scope.thisSlot()).at(line);
                } else {
                    ParsedCallArgs pca = parseCallArgDetails();
                    e = new CallValueNode(e, pca.args(), pca.refs(), pca.names(), pca.spreads(), scope.thisSlot()).at(line);
                }
            } else {
                break;
            }
        }
        return e;
    }

    private boolean consumeFirstClassCallableEllipsis() {
        if (peek().isOp("...") && peek(1).isOp(")")) {
            next();
            next();
            return true;
        }
        return false;
    }

    private PhpNode parseMemberAccess(PhpNode target, int line, boolean nullsafe) {
        PhpNode nodeTarget = nullsafe ? null : target;
        PhpNode access;
        if (peek().kind() == Kind.IDENT) {
            String member = next().text();
            if (acceptOp("(")) {
                access = parseMemberCallableOrCall(nodeTarget, member, language.methodId(member), null, line, nullsafe);
            } else {
                access = new PropertyLValueNode(nodeTarget, member, null, currentClassLower()).at(line);
            }
        } else if (peek().kind() == Kind.VARIABLE) {
            Token vt = next();
            PhpNode nameExpr = scope.variable(vt.text()).at(vt.line());
            if (acceptOp("(")) {
                access = parseMemberCallableOrCall(nodeTarget, null, -1, nameExpr, line, nullsafe);
            } else {
                access = new PropertyLValueNode(nodeTarget, null, nameExpr, currentClassLower()).at(line);
            }
        } else if (acceptOp("{")) {
            PhpNode nameExpr = expression();
            expectOp("}");
            if (acceptOp("(")) {
                access = parseMemberCallableOrCall(nodeTarget, null, -1, nameExpr, line, nullsafe);
            } else {
                access = new PropertyLValueNode(nodeTarget, null, nameExpr, currentClassLower()).at(line);
            }
        } else {
            throw error("syntax error, unexpected " + describe(peek())
                    + (nullsafe ? " after \"?->\"" : " after \"->\""));
        }
        if (!nullsafe) {
            return access;
        }
        if (access instanceof MethodCallNode mc) {
            return new NullsafeMemberNode(target, mc).at(line);
        }
        return new NullsafeMemberNode(target, (PropertyLValueNode) access).at(line);
    }

    private PhpNode parseMemberCallableOrCall(PhpNode nodeTarget, String member, int methodId,
                                             PhpNode nameExpr, int line, boolean nullsafe) {
        if (consumeFirstClassCallableEllipsis()) {
            if (nullsafe) {
                throw error("Cannot combine nullsafe operator with Closure creation");
            }
            return FirstClassCallableNode.forMethod(nodeTarget, member, methodId, nameExpr).at(line);
        }
        ParsedCallArgs pca = parseCallArgDetails();
        return new MethodCallNode(nodeTarget, member, methodId, nameExpr, pca.args(), pca.refs(), pca.names(), pca.spreads()).at(line);
    }

    private PhpNode parseStaticMember(ClassRefNode classRef, int line) {
        if (peek().kind() == Kind.IDENT) {
            String member = next().text();
            if (acceptOp("(")) {
                return parseStaticCallableOrCall(classRef, member, language.methodId(member), null, line);
            }
            return new ClassConstNode(classRef, member).at(line);
        }
        if (peek().kind() == Kind.VARIABLE) {
            Token vt = next();
            if (acceptOp("(")) {
                PhpNode nameExpr = scope.variable(vt.text()).at(vt.line());
                return parseStaticCallableOrCall(classRef, null, -1, nameExpr, line);
            }
            return new StaticPropertyLValueNode(classRef, vt.text(), null, currentClassLower()).at(line);
        }
        if (acceptOp("{")) {
            PhpNode nameExpr = expression();
            expectOp("}");
            expectOp("(");
            return parseStaticCallableOrCall(classRef, null, -1, nameExpr, line);
        }
        throw error("syntax error, unexpected " + describe(peek()) + " after \"::\"");
    }

    private PhpNode parseStaticCallableOrCall(ClassRefNode classRef, String member, int methodId,
                                             PhpNode nameExpr, int line) {
        if (consumeFirstClassCallableEllipsis()) {
            return FirstClassCallableNode.forStaticMethod(
                    classRef, member, methodId, nameExpr, scope.thisSlot(), scope.calledClassSlot()).at(line);
        }
        ParsedCallArgs pca = parseCallArgDetails();
        return new StaticMethodCallNode(classRef, member, methodId, nameExpr,
                scope.thisSlot(), scope.calledClassSlot(), pca.args(), pca.refs(), pca.names(), pca.spreads()).at(line);
    }

    private record ParsedCallArgs(PhpNode[] args, LValueNode[] refs, String[] names, boolean[] spreads) {
    }

    private ParsedCallArgs parseCallArgDetails() {
        List<PhpNode> args = new ArrayList<>();
        List<LValueNode> refs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<Boolean> spreads = new ArrayList<>();
        boolean seenNamed = false;
        boolean anyNamed = false;
        boolean anySpread = false;
        while (!acceptOp(")")) {
            if (acceptOp("...")) {
                if (seenNamed) {
                    throw error("Cannot use argument unpacking after named arguments");
                }
                anySpread = true;
                PhpNode expr = expression();
                args.add(expr);
                refs.add(lvalueOrNull(expr));
                names.add(null);
                spreads.add(true);
            } else if (peek().kind() == Kind.IDENT && peek(1).isOp(":")) {
                String argName = next().text();
                next(); // ':'
                seenNamed = true;
                anyNamed = true;
                PhpNode expr = expression();
                args.add(expr);
                refs.add(lvalueOrNull(expr));
                names.add(argName);
                spreads.add(false);
            } else {
                if (seenNamed) {
                    throw error("Cannot use positional argument after named argument");
                }
                PhpNode expr = expression();
                args.add(expr);
                refs.add(lvalueOrNull(expr));
                names.add(null);
                spreads.add(false);
            }
            if (!peek().isOp(")")) {
                expectOp(",");
            }
        }
        PhpNode[] argArr = args.toArray(new PhpNode[0]);
        LValueNode[] refArr = refs.toArray(new LValueNode[0]);
        String[] nameArr = anyNamed ? names.toArray(new String[0]) : null;
        boolean[] spreadArr = null;
        if (anySpread) {
            spreadArr = new boolean[spreads.size()];
            for (int i = 0; i < spreads.size(); i++) {
                spreadArr[i] = spreads.get(i);
            }
        }
        return new ParsedCallArgs(argArr, refArr, nameArr, spreadArr);
    }

    private PhpNode[] parseCallArgs() {
        return parseCallArgDetails().args();
    }

    private ClassRefNode makeClassRefFromName(String name) {
        String lower = name.toLowerCase();
        String cur = currentClass != null ? currentClass : "<global>";
        return switch (lower) {
            case "self" -> ClassRefNode.self(cur, language.classId(cur));
            case "parent" -> ClassRefNode.parent(cur, language.classId(cur));
            case "static" -> ClassRefNode.staticRef(scope.calledClassSlot());
            default -> ClassRefNode.named(name, language.classId(name));
        };
    }

    private ClassRefNode parseClassRef(boolean allowDynamic) {
        Token t = peek();
        if (t.kind() == Kind.IDENT) {
            next();
            return makeClassRefFromName(t.text());
        }
        if (t.kind() == Kind.VARIABLE) {
            return ClassRefNode.dynamic(postfixOnly(false));
        }
        if (allowDynamic && t.isOp("(")) {
            return ClassRefNode.dynamic(primary());
        }
        throw error("syntax error, unexpected " + describe(t) + ", expecting class name");
    }

    private PhpNode parseNew(int line) {
        skipAttributes();
        if (acceptKeyword("class")) {
            ParsedCallArgs pca = acceptOp("(") ? parseCallArgDetails()
                    : new ParsedCallArgs(new PhpNode[0], new LValueNode[0], null, null);
            long tid = language.nextTemplateId();
            String anonName = "class@anonymous\0" + magicFile() + ":" + line + "$" + tid;
            PhpClassTemplate tpl = parseClassTemplate(tid, anonName, false, false, false, true);
            ClassRefNode ref = ClassRefNode.anonymous(tpl, language.classId(anonName));
            return new NewNode(ref, pca.args(), pca.refs(), pca.names(), pca.spreads()).at(line);
        }
        ClassRefNode ref = parseClassRef(false);
        ParsedCallArgs pca = acceptOp("(") ? parseCallArgDetails()
                : new ParsedCallArgs(new PhpNode[0], new LValueNode[0], null, null);
        return new NewNode(ref, pca.args(), pca.refs(), pca.names(), pca.spreads()).at(line);
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
        if (lower.equals("new")) {
            return parseNew(line);
        }
        if (lower.equals("function") && (peek().isOp("(") || (peek().isOp("&") && peek(1).isOp("(")))) {
            return parseClosure(line, false);
        }
        if (lower.equals("fn") && (peek().isOp("(") || (peek().isOp("&") && peek(1).isOp("(")))) {
            return parseArrowFunction(line, false);
        }
        if (lower.equals("static")) {
            if (peek().kind() == Kind.IDENT && peek().text().equalsIgnoreCase("function")
                    && (peek(1).isOp("(") || (peek(1).isOp("&") && peek(2).isOp("(")))) {
                next();
                return parseClosure(line, true);
            }
            if (peek().kind() == Kind.IDENT && peek().text().equalsIgnoreCase("fn")
                    && (peek(1).isOp("(") || (peek(1).isOp("&") && peek(2).isOp("(")))) {
                next();
                return parseArrowFunction(line, true);
            }
        }
        if (peek().isOp("::")) {
            next();
            return parseStaticMember(makeClassRefFromName(name), line);
        }
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
            case "__file__" -> {
                return new ConstNode(magicFile()).at(line);
            }
            case "__dir__" -> {
                return new ConstNode(magicDir()).at(line);
            }
            case "__line__" -> {
                return new ConstNode((long) line).at(line);
            }
            case "__function__" -> {
                return new ConstNode(currentFunction).at(line);
            }
            case "__class__" -> {
                return new ConstNode(currentClass != null ? currentClass : "").at(line);
            }
            case "__method__" -> {
                String m = currentClass != null && !currentFunction.isEmpty()
                        ? currentClass + "::" + currentFunction
                        : currentFunction;
                return new ConstNode(m).at(line);
            }
            case "__namespace__" -> {
                return new ConstNode("").at(line);
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
            case "func_num_args", "func_get_args", "func_get_arg" -> {
                if (peek().isOp("(") && !(peek(1).isOp("...") && peek(2).isOp(")"))) {
                    next();
                    PhpNode[] args = parseCallArgs();
                    return switch (lower) {
                        case "func_num_args" -> new FuncArgsNode(FuncArgsNode.Kind.NUM_ARGS, scope.argOffset(), null).at(line);
                        case "func_get_args" -> new FuncArgsNode(FuncArgsNode.Kind.GET_ARGS, scope.argOffset(), null).at(line);
                        default -> {
                            if (args.length != 1) {
                                throw error(name + "() expects exactly 1 argument, " + args.length + " given");
                            }
                            yield new FuncArgsNode(FuncArgsNode.Kind.GET_ARG, scope.argOffset(), args[0]).at(line);
                        }
                    };
                }
            }
            default -> {
            }
        }
        if (peek().isOp("(")) {
            next();
            if (consumeFirstClassCallableEllipsis()) {
                return FirstClassCallableNode.forFunction(name, language.functionId(name)).at(line);
            }
            ParsedCallArgs pca = parseCallArgDetails();
            return new CallNode(name, language.functionId(name), pca.args(), pca.refs(), pca.names(), pca.spreads()).at(line);
        }
        Object constant = PhpConstants.compileTime(name);
        if (constant != null) {
            return new ConstNode(constant).at(line);
        }
        return new ReadConstantNode(name).at(line);
    }

    private String magicFile() {
        String path = source.getPath();
        if (path == null || path.isEmpty()) {
            return "";
        }
        return Path.of(path).toAbsolutePath().normalize().toString();
    }

    private String magicDir() {
        String file = magicFile();
        if (file.isEmpty()) {
            return "";
        }
        Path parent = Path.of(file).getParent();
        return parent == null ? "" : parent.toString();
    }

    private PhpNode arrayLiteral(String closing) {
        List<PhpNode> keys = new ArrayList<>();
        List<PhpNode> values = new ArrayList<>();
        List<Boolean> byRefs = new ArrayList<>();
        List<Boolean> spreads = new ArrayList<>();
        while (!acceptOp(closing)) {
            if (acceptOp("...")) {
                keys.add(null);
                values.add(expression());
                byRefs.add(false);
                spreads.add(true);
            } else if (acceptOp("&")) {
                PhpNode refVal = expression();
                markCellIfLocal(refVal);
                keys.add(null);
                values.add(refVal);
                byRefs.add(true);
                spreads.add(false);
            } else {
                PhpNode first = expression();
                if (acceptOp("=>")) {
                    keys.add(first);
                    boolean isRef = acceptOp("&");
                    PhpNode val = expression();
                    if (isRef) {
                        markCellIfLocal(val);
                    }
                    values.add(val);
                    byRefs.add(isRef);
                    spreads.add(false);
                } else {
                    keys.add(null);
                    values.add(first);
                    byRefs.add(false);
                    spreads.add(false);
                }
            }
            if (!peek().isOp(closing)) {
                expectOp(",");
            }
        }
        boolean[] refArr = new boolean[byRefs.size()];
        boolean[] spreadArr = new boolean[spreads.size()];
        for (int i = 0; i < byRefs.size(); i++) {
            refArr[i] = byRefs.get(i);
            spreadArr[i] = spreads.get(i);
        }
        return new ArrayLiteralNode(keys.toArray(new PhpNode[0]), values.toArray(new PhpNode[0]), refArr, spreadArr);
    }

    private static void markCellIfLocal(PhpNode node) {
        if (node instanceof LocalVarNode lv && !"this".equals(lv.varName())) {
            lv.kind().markCell();
        }
    }

    private PhpNode interpolation(Token t) {
        List<PhpNode> parts = new ArrayList<>();
        for (Token.Part part : t.parts()) {
            if (!part.isExpr()) {
                parts.add(new ConstNode(part.value()));
                continue;
            }
            List<Token> sub = Lexer.tokenizeCode(part.value());
            Parser p = new Parser(language, source, sub, scope, functions, topLevelClasses, currentFunction, currentClass);
            PhpNode e = p.expression();
            if (p.peek().kind() != Kind.EOF) {
                throw new PhpParseError("syntax error in string interpolation", t.line());
            }
            parts.add(e);
        }
        return new InterpolateNode(parts.toArray(new PhpNode[0]));
    }
}
