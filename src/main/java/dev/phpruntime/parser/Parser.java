package dev.phpruntime.parser;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.source.Source;
import dev.phpruntime.PhpLanguage;
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
import java.util.HashMap;
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
    private boolean currentIsTrait;
    private String currentNamespace;
    private final Map<String, String> classAliases;
    private final Map<String, String> functionAliases;
    private final Map<String, String> constAliases;
    private boolean currentReturnsRef;
    private int hoistedClassesSlot = -1;
    private int pos;

    public Parser(PhpLanguage language, Source source) {
        this(language, source, lex(source), new Scope(language, true), new ArrayList<>(), new ArrayList<>(),
                "", null, false, "", new HashMap<>(), new HashMap<>(), new HashMap<>());
    }

    public Parser(PhpLanguage language, Source source, boolean localInclude) {
        this(language, source);
        if (localInclude) { scope = Scope.forInclude(language); }
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
                   String currentFunction, String currentClass, boolean currentIsTrait,
                   String currentNamespace,
                   Map<String, String> classAliases, Map<String, String> functionAliases,
                   Map<String, String> constAliases) {
        this.language = language;
        this.source = source;
        this.tokens = tokens;
        this.scope = scope;
        this.functions = functions;
        this.topLevelClasses = topLevelClasses;
        this.currentFunction = currentFunction;
        this.currentClass = currentClass;
        this.currentIsTrait = currentIsTrait;
        this.currentNamespace = currentNamespace;
        this.classAliases = classAliases;
        this.functionAliases = functionAliases;
        this.constAliases = constAliases;
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
            scope.validateGotos();
            PhpNode body = block(stmts);
            return new PhpMainRootNode(language, scope.build(), source, body,
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
        return currentClass == null ? null : dev.phpruntime.runtime.PhpStrings.lower(currentClass);
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
            String kw = dev.phpruntime.runtime.PhpStrings.lower(t.text());
            if (peek(1).isOp(":") && !kw.equals("default") && !kw.equals("case") && !kw.equals("goto")) {
                next();
                next();
                int labelId = scope.gotoScope().defineLabel(t.text(), line);
                return new LabelMarkerNode(labelId);
            }
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
                    PhpNode body;
                    scope.gotoScope().enterLoop();
                    try {
                        body = statementOrEmpty();
                    } finally {
                        scope.gotoScope().exitLoop();
                    }
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
                case "goto" -> {
                    next();
                    Token lbl = expectIdent();
                    endStatement();
                    int labelId = scope.gotoScope().useGoto(lbl.text(), line);
                    return new GotoNode(labelId).at(line);
                }
                case "function" -> {
                    if (peek(1).kind() == Kind.IDENT || (peek(1).isOp("&") && peek(2).kind() == Kind.IDENT)) {
                        next();
                        return functionDeclaration(topLevel, line);
                    }
                }
                case "const" -> {
                    if (!scope.isFileScope()) {
                        throw error("const declarations are only allowed at top level");
                    }
                    next();
                    return constStatement(line);
                }
                case "global" -> {
                    next();
                    List<Integer> slots = new ArrayList<>();
                    List<Integer> globalIds = new ArrayList<>();
                    List<LValueNode> dynamicTargets = new ArrayList<>();
                    List<PhpNode> dynamicNames = new ArrayList<>();
                    List<LValueNode> fileScopeGlobals = new ArrayList<>();
                    do {
                        if (peek().isOp("$") || scope.isFileScope() && !scope.isGlobal()) {
                            PhpNode nameExpr;
                            if (peek().kind() == Kind.VARIABLE) { nameExpr = new ConstNode(next().text()); }
                            else {
                                expectOp("$");
                                nameExpr = variableNameOperand();
                            }
                            dynamicNames.add(nameExpr);
                            dynamicTargets.add(new DynamicVarNode(new ConstNode(""), scope.dynamicSlot()));
                            continue;
                        }
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
                        } else {
                            fileScopeGlobals.add(scope.variable(name).at(v.line()));
                        }
                    } while (acceptOp(","));
                    endStatement();
                    if (!dynamicTargets.isEmpty()) {
                        PhpNode dynamic = new GlobalImportNode(dynamicTargets.toArray(new LValueNode[0]), dynamicNames.toArray(new PhpNode[0])).at(line);
                        if (slots.isEmpty()) { return dynamic; }
                        return new BlockNode(new PhpNode[]{new GlobalImportNode(toIntArray(slots), toIntArray(globalIds)), dynamic}).at(line);
                    }
                    if (slots.isEmpty()) {
                        return fileScopeGlobals.isEmpty() ? null
                                : new dev.phpruntime.nodes.stmt.EnsureVariablesNode(
                                        fileScopeGlobals.toArray(new LValueNode[0])).at(line);
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
                case "abstract", "final", "readonly", "class", "interface", "trait" -> {
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
                case "namespace" -> {
                    if (!topLevel) {
                        throw error("Namespace declaration statement has to be at top level");
                    }
                    next();
                    return namespaceStatement();
                }
                case "use" -> {
                    next();
                    parseUseStatement();
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
        if (s == null) {
            return plainBlock(List.of());
        }
        return s instanceof LabelMarkerNode ? block(List.of(s)) : s;
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

    private static PhpNode plainBlock(List<PhpNode> stmts) {
        if (stmts.size() == 1) {
            return stmts.get(0);
        }
        return new BlockNode(stmts.toArray(new PhpNode[0]));
    }

    private PhpNode block(List<PhpNode> stmts) {
        boolean hasGoto = false;
        for (PhpNode s : stmts) {
            if (s instanceof LabelMarkerNode || (s instanceof GotoEnterable ge && ge.exportedLabelIds().length > 0)) {
                hasGoto = true;
                break;
            }
        }
        if (!hasGoto) {
            return plainBlock(stmts);
        }
        List<PhpNode> segments = new ArrayList<>();
        List<PhpNode> cur = new ArrayList<>();
        List<Integer> directIds = new ArrayList<>();
        List<Integer> directSegs = new ArrayList<>();
        List<Integer> childIds = new ArrayList<>();
        List<Integer> childSegs = new ArrayList<>();
        int maxDirectSeg = -1;
        for (PhpNode s : stmts) {
            if (s instanceof LabelMarkerNode lm) {
                if (!cur.isEmpty()) {
                    segments.add(plainBlock(cur));
                    cur.clear();
                }
                int seg = segments.size();
                directIds.add(lm.labelId);
                directSegs.add(seg);
                if (seg > maxDirectSeg) {
                    maxDirectSeg = seg;
                }
            } else if (s instanceof GotoEnterable ge && ge.exportedLabelIds().length > 0) {
                if (!cur.isEmpty()) {
                    segments.add(plainBlock(cur));
                    cur.clear();
                }
                int seg = segments.size();
                segments.add(s);
                for (int cid : ge.exportedLabelIds()) {
                    childIds.add(cid);
                    childSegs.add(seg);
                }
            } else {
                cur.add(s);
            }
        }
        if (!cur.isEmpty()) {
            segments.add(plainBlock(cur));
        } else if (segments.isEmpty() || maxDirectSeg >= segments.size()) {
            segments.add(new BlockNode(new PhpNode[0]));
        }
        int pcSlot = scope.intSlot("@gotoPc_" + scope.gotoScope().nextDispatchId());
        int enterSlot = childIds.isEmpty() ? -1 : scope.intSlot("@gotoEnter_" + scope.gotoScope().nextDispatchId());
        return new GotoDispatchNode(
                pcSlot, enterSlot,
                segments.toArray(new PhpNode[0]),
                toIntArray(directIds), toIntArray(directSegs),
                toIntArray(childIds), toIntArray(childSegs));
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private static final class LabelMarkerNode extends PhpNode {
        final int labelId;

        LabelMarkerNode(int labelId) {
            this.labelId = labelId;
        }

        @Override
        public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) {
            return null;
        }
    }

    private PhpNode parenExpression() {
        expectOp("(");
        PhpNode e = expression();
        expectOp(")");
        return e;
    }

    private PhpNode makeIfNode(PhpNode cond, PhpNode then, PhpNode otherwise, int line) {
        if ((then instanceof GotoEnterable ge1 && ge1.exportedLabelIds().length > 0)
                || (otherwise instanceof GotoEnterable ge2 && ge2.exportedLabelIds().length > 0)) {
            return new GotoIfNode(cond, then, otherwise).at(line);
        }
        return new IfNode(cond, then, otherwise).at(line);
    }

    private PhpNode ifStatement() {
        int line = peek().line();
        PhpNode cond = parenExpression();
        if (acceptOp(":")) {
            PhpNode then = blockUntilKeyword("elseif", "else", "endif");
            PhpNode otherwise = null;
            if (acceptKeyword("elseif")) {
                otherwise = ifStatement();
                return makeIfNode(cond, then, otherwise, line);
            }
            if (acceptKeyword("else")) {
                if (peek().isKeyword("if")) {
                    next();
                    otherwise = ifStatement();
                    return makeIfNode(cond, then, otherwise, line);
                }
                expectOp(":");
                otherwise = blockUntilKeyword("endif");
            }
            expectKeyword("endif");
            endStatement();
            return makeIfNode(cond, then, otherwise, line);
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
        return makeIfNode(cond, then, otherwise, line);
    }

    private PhpNode whileStatement() {
        int line = peek().line();
        PhpNode cond = parenExpression();
        PhpNode body;
        scope.gotoScope().enterLoop();
        try {
            if (acceptOp(":")) {
                body = blockUntilKeyword("endwhile");
                next();
                endStatement();
            } else {
                body = statementOrEmpty();
            }
        } finally {
            scope.gotoScope().exitLoop();
        }
        return LoopStmtNode.whileLoop(cond, body).at(line);
    }

    private PhpNode forStatement() {
        int line = peek().line();
        expectOp("(");
        PhpNode init = peek().isOp(";") ? null : plainBlock(List.of(exprList()));
        expectOp(";");
        PhpNode cond = null;
        if (!peek().isOp(";")) {
            PhpNode[] conds = exprList();
            // In "for(a; b, c; d)" the last expression decides.
            cond = conds.length == 1 ? conds[0] : new BlockValueNode(conds);
        }
        expectOp(";");
        PhpNode update = peek().isOp(")") ? null : plainBlock(List.of(exprList()));
        expectOp(")");
        PhpNode body;
        scope.gotoScope().enterLoop();
        try {
            if (acceptOp(":")) {
                body = blockUntilKeyword("endfor");
                next();
                endStatement();
            } else {
                body = statementOrEmpty();
            }
        } finally {
            scope.gotoScope().exitLoop();
        }
        return LoopStmtNode.forLoop(init, cond, update, body).at(line);
    }

    private PhpNode foreachStatement() {
        int line = peek().line();
        expectOp("(");
        PhpNode subject = expression();
        expectKeyword("as");
        boolean byRef = acceptOp("&");
        LValueNode first = parseForeachTarget(byRef);
        LValueNode key = null;
        LValueNode value = first;
        if (acceptOp("=>")) {
            if (byRef) {
                throw error("Key element cannot be a reference");
            }
            if (first instanceof DestructureLValueNode) {
                throw error("Cannot use list as key element");
            }
            byRef = acceptOp("&");
            key = first;
            value = parseForeachTarget(byRef);
        }
        if (!byRef && value instanceof DestructureLValueNode dest && dest.hasAnyRef()) {
            byRef = true;
        }
        if (byRef && value instanceof LocalVarNode lv) {
            lv.kind().markCell();
        }
        expectOp(")");
        PhpNode body;
        scope.gotoScope().enterLoop();
        try {
            if (acceptOp(":")) {
                body = blockUntilKeyword("endforeach");
                next();
                endStatement();
            } else {
                body = statementOrEmpty();
            }
        } finally {
            scope.gotoScope().exitLoop();
        }
        return new ForeachNode(language, subject, lvalueOrNull(subject), key, value, byRef, body).at(line);
    }

    private LValueNode parseForeachTarget(boolean outerByRef) {
        int line = peek().line();
        if (peek().isOp("[")) {
            if (outerByRef) {
                throw error("Cannot assign reference to list()");
            }
            next();
            return parseDestructurePattern("]").at(line);
        }
        if (peek().isKeyword("list") && peek(1).isOp("(")) {
            if (outerByRef) {
                throw error("Cannot assign reference to list()");
            }
            next();
            next();
            return parseDestructurePattern(")").at(line);
        }
        return lvalue(postfixOnly());
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
        scope.gotoScope().enterLoop();
        try {
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
        } finally {
            scope.gotoScope().exitLoop();
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
        StaticDeclNode node = new StaticDeclNode(
                scope.isGlobal(), scope.closureObjSlot(),
                names.toArray(new String[0]),
                slots.stream().mapToInt(Integer::intValue).toArray(),
                globalIds.stream().mapToInt(Integer::intValue).toArray(),
                inits.toArray(new PhpNode[0])).at(line);
        if (scope.isFileScope() && !scope.isGlobal()) { node.includedScope(scope.dynamicSlot()); }
        return node;
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
                String typeName = resolveClassName(expectIdent().text());
                String lower = dev.phpruntime.runtime.PhpStrings.lower(typeName);
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
            scope.gotoScope().enterFinally();
            try {
                finallyBlock = blockUntil("}");
            } finally {
                scope.gotoScope().exitFinally();
            }
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
            names.add(qualifyDeclaration(nameTok.text()));
            values.add(expression());
        } while (acceptOp(","));
        endStatement();
        return new DefineConstantNode(names.toArray(new String[0]), values.toArray(new PhpNode[0])).at(line);
    }

    private record PromotedProp(String name, Visibility visibility, boolean isReadonly, boolean byRef, int line) {
    }

    private record ParsedParams(String[] names, int[] slots, VarKind[] kinds, boolean[] byRef,
                                PhpNode[] defaults, boolean variadic, List<PromotedProp> promoted) {}

    private ParsedParams parseParams() {
        return parseParams(false);
    }

    private ParsedParams parseParams(boolean allowPromotion) {
        expectOp("(");
        List<String> names = new ArrayList<>();
        List<Integer> slots = new ArrayList<>();
        List<Boolean> byRef = new ArrayList<>();
        List<PhpNode> defaults = new ArrayList<>();
        List<PromotedProp> promoted = new ArrayList<>();
        boolean variadic = false;
        while (!acceptOp(")")) {
            skipAttributes();
            Visibility promVis = null;
            boolean promReadonly = false;
            boolean parsingMods = true;
            while (parsingMods && peek().kind() == Kind.IDENT) {
                switch (dev.phpruntime.runtime.PhpStrings.lower(peek().text())) {
                    case "public" -> { next(); promVis = Visibility.PUBLIC; }
                    case "protected" -> { next(); promVis = Visibility.PROTECTED; }
                    case "private" -> { next(); promVis = Visibility.PRIVATE; }
                    case "readonly" -> { next(); promReadonly = true; }
                    default -> parsingMods = false;
                }
            }
            if (promVis == null && promReadonly) {
                promVis = Visibility.PUBLIC;
            }
            if (promVis != null && !allowPromotion) {
                throw error("Cannot declare promoted property outside a constructor");
            }
            skipType();
            boolean isRef = acceptOp("&");
            boolean isVarArg = acceptOp("...");
            if (isVarArg) {
                if (promVis != null) {
                    throw error("Cannot declare variadic promoted property");
                }
                variadic = true;
            }
            Token v = next();
            if (v.kind() != Kind.VARIABLE) {
                throw new PhpParseError("syntax error, expected parameter variable", v.line());
            }
            String pName = v.text();
            scope.declareParam(pName, isVarArg);
            if (isRef && !isVarArg) {
                scope.markCell(pName);
            }
            names.add(pName);
            slots.add(scope.slot(pName));
            byRef.add(isRef);
            defaults.add(acceptOp("=") ? expression() : null);
            if (promVis != null) {
                promoted.add(new PromotedProp(pName, promVis, promReadonly, isRef, v.line()));
            }
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
        return new ParsedParams(names.toArray(new String[0]), slotArray, kindArray, refArray,
                defaults.toArray(new PhpNode[0]), variadic, promoted);
    }

    private PhpNode functionDeclaration(boolean topLevel, int line) {
        boolean returnsRef = acceptOp("&");
        Token nameTok = next();
        String name = qualifyDeclaration(nameTok.text());
        Scope outer = scope;
        String outerFunction = currentFunction;
        String outerClass = currentClass;
        boolean outerIsTrait = currentIsTrait;
        boolean outerReturnsRef = currentReturnsRef;
        scope = new Scope(language, false);
        currentFunction = name;
        currentClass = null;
        currentIsTrait = false;
        currentReturnsRef = returnsRef;
        try {
            ParsedParams params = parseParams();
            if (acceptOp(":")) {
                skipType();
            }
            expectOp("{");
            PhpNode body = blockUntil("}");
            scope.validateGotos();
            PhpFunctionRootNode root = DynamicScopeLowering.finish(scope.isGenerator() ? body : null,
                    new PhpFunctionRootNode(
                    language, scope.build(), source, nameTok.line(),
                    null, name, false, returnsRef, -1, -1, -1,
                    new int[0], new VarKind[0], new boolean[0],
                    params.names(), params.slots(), params.kinds(), params.byRef(),
                    params.defaults(), params.variadic(), body));
            if (scope.isGenerator()) {
                root.asGenerator();
            }
            PhpFunction fn = new PhpFunction(name, dev.phpruntime.bytecode.BytecodeEmitter.target(language, root));
            if (topLevel) {
                functions.add(fn);
                return null;
            }
            return new DeclareFunctionNode(fn).at(line);
        } finally {
            scope = outer;
            currentFunction = outerFunction;
            currentClass = outerClass;
            currentIsTrait = outerIsTrait;
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
            scope.validateGotos();
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
        PhpFunctionRootNode root = DynamicScopeLowering.finish(scope.isGenerator() ? body : null,
                new PhpFunctionRootNode(
                language, scope.build(), source, line,
                currentClass, fnName, !bindThis, returnsRef, scope.thisSlot(), scope.calledClassSlot(),
                scope.closureObjSlot(), scope.traitSelfSlot(),
                captureSlots, captureKinds, byRef,
                params.names(), params.slots(), params.kinds(), params.byRef(),
                params.defaults(), params.variadic(), body).closureMetadata(scope.usesThis()));
        if (scope.isGenerator()) {
            root.asGenerator();
        }
        return new ClosureNode(dev.phpruntime.bytecode.BytecodeEmitter.target(language, root), bindThis,
                outer.thisSlot(), outer.calledClassSlot(), outer.traitSelfSlot(), outerReads, warn, byRef)
                .inClass(currentClass != null ? language.classId(currentClass) : -1).at(line);
    }

    private PhpNode classDeclaration(boolean topLevel, int line) {
        boolean isAbstract = false;
        boolean isFinal = false;
        boolean isReadonly = false;
        boolean mods = true;
        while (mods && peek().kind() == Kind.IDENT) {
            switch (dev.phpruntime.runtime.PhpStrings.lower(peek().text())) {
                case "abstract" -> { next(); isAbstract = true; }
                case "final" -> { next(); isFinal = true; }
                case "readonly" -> { next(); isReadonly = true; }
                default -> mods = false;
            }
        }
        boolean isTrait = acceptKeyword("trait");
        boolean isInterface = !isTrait && acceptKeyword("interface");
        if (!isTrait && !isInterface) {
            expectKeyword("class");
        }
        String name = qualifyDeclaration(expectIdent().text());
        PhpClassTemplate template = parseClassTemplate(
                language.nextTemplateId(), name, isInterface, isTrait, isAbstract, isFinal, isReadonly, false);
        if (topLevel) {
            int idx = topLevelClasses.size();
            topLevelClasses.add(template);
            return new DeclareClassNode(template, hoistedClassesSlot(), idx).at(line);
        }
        return new DeclareClassNode(template, -1, -1).at(line);
    }

    private PhpClassTemplate parseClassTemplate(long templateId, String name, boolean isInterface, boolean isTrait,
                                                  boolean isAbstract, boolean isFinal, boolean classReadonly,
                                                  boolean isAnonymous) {
        String parentName = null;
        List<String> interfaceNames = new ArrayList<>();
        if (acceptKeyword("extends")) {
            if (isInterface) {
                do {
                    interfaceNames.add(resolveClassName(expectIdent().text()));
                } while (acceptOp(","));
            } else {
                parentName = resolveClassName(expectIdent().text());
            }
        }
        if (!isInterface && acceptKeyword("implements")) {
            do {
                interfaceNames.add(resolveClassName(expectIdent().text()));
            } while (acceptOp(","));
        }
        expectOp("{");
        List<PhpClassTemplate.TraitUse> traitUses = new ArrayList<>();
        List<PhpClassTemplate.PropertyDecl> properties = new ArrayList<>();
        List<PhpClassTemplate.ConstantDecl> constants = new ArrayList<>();
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        String outerClass = currentClass;
        boolean outerIsTrait = currentIsTrait;
        currentClass = name;
        currentIsTrait = isTrait;
        try {
            while (!acceptOp("}")) {
                if (peek().kind() == Kind.EOF) {
                    throw error("syntax error, unexpected end of file in class " + name);
                }
                skipAttributes();
                if (acceptOp("}")) {
                    break;
                }
                if (acceptKeyword("use")) {
                    traitUses.add(parseTraitUse());
                    continue;
                }
                Visibility vis = Visibility.PUBLIC;
                boolean isStatic = false;
                boolean mAbstract = false;
                boolean mFinal = false;
                boolean pReadonly = classReadonly;
                boolean explicitReadonly = false;
                boolean parsingMods = true;
                while (parsingMods && peek().kind() == Kind.IDENT) {
                    switch (dev.phpruntime.runtime.PhpStrings.lower(peek().text())) {
                        case "public", "var" -> { next(); vis = Visibility.PUBLIC; }
                        case "protected" -> { next(); vis = Visibility.PROTECTED; }
                        case "private" -> { next(); vis = Visibility.PRIVATE; }
                        case "static" -> { next(); isStatic = true; }
                        case "abstract" -> { next(); mAbstract = true; }
                        case "final" -> { next(); mFinal = true; }
                        case "readonly" -> { next(); pReadonly = true; explicitReadonly = true; }
                        default -> parsingMods = false;
                    }
                }
                if (acceptKeyword("const")) {
                    if ((peek().isOp("?") || peek().kind() == Kind.IDENT) && !peek(1).isOp("=")) {
                        skipType();
                    }
                    do {
                        String constName = expectIdent().text();
                        expectOp("=");
                        Scope[] exprScope = new Scope[1];
                        PhpNode expr = parseMemberInitializer(isTrait, exprScope);
                        Object constVal = expr instanceof ConstNode c ? c.value() : null;
                        RootCallTarget valTarget = memberInitTarget(expr, exprScope[0]);
                        constants.add(new PhpClassTemplate.ConstantDecl(constName, vis, mFinal, constVal, valTarget));
                    } while (acceptOp(","));
                    expectOp(";");
                } else if (acceptKeyword("function")) {
                    PhpClassTemplate.MethodDecl m = parseMethod(vis, isStatic, isInterface || mAbstract, mFinal,
                            classReadonly, properties);
                    methods.put(m.lowerName(), m);
                } else {
                    if (isStatic && (explicitReadonly || classReadonly)) {
                        throw error("Static property cannot be readonly");
                    }
                    skipType();
                    do {
                        Token propTok = next();
                        if (propTok.kind() != Kind.VARIABLE) {
                            throw new PhpParseError("syntax error, expected property variable", propTok.line());
                        }
                        Scope[] exprScope = new Scope[1];
                        PhpNode expr = acceptOp("=") ? parseMemberInitializer(isTrait, exprScope) : null;
                        if (pReadonly && expr != null) {
                            throw new PhpParseError("Readonly property " + name + "::$" + propTok.text() + " cannot have default value", propTok.line());
                        }
                        Object constVal = expr == null ? PhpNull.INSTANCE
                                : (expr instanceof ConstNode c ? c.value() : null);
                        RootCallTarget valTarget = expr == null ? null : memberInitTarget(expr, exprScope[0]);
                        properties.add(new PhpClassTemplate.PropertyDecl(propTok.text(), vis, isStatic, pReadonly, constVal, valTarget));
                    } while (acceptOp(","));
                    expectOp(";");
                }
            }
        } finally {
            currentClass = outerClass;
            currentIsTrait = outerIsTrait;
        }
        return new PhpClassTemplate(
                templateId, name, isInterface, isTrait, isAbstract, isFinal, isAnonymous,
                parentName, interfaceNames, traitUses, properties, constants, methods);
    }

    private PhpNode parseMemberInitializer(boolean isTrait, Scope[] outExprScope) {
        if (!isTrait) {
            return expression();
        }
        Scope outer = scope;
        Scope exprScope = Scope.forTraitMethod(language);
        scope = exprScope;
        outExprScope[0] = exprScope;
        try {
            return expression();
        } finally {
            scope = outer;
        }
    }

    private RootCallTarget memberInitTarget(PhpNode expr, Scope exprScope) {
        if (expr instanceof ConstNode) {
            return null;
        }
        int traitSelfSlot = exprScope != null ? exprScope.traitSelfSlot() : -1;
        return dev.phpruntime.bytecode.BytecodeEmitter.exprTarget(language, source, expr, traitSelfSlot);
    }

    private PhpClassTemplate.TraitUse parseTraitUse() {
        List<String> traitNames = new ArrayList<>();
        do {
            traitNames.add(resolveClassName(expectIdent().text()));
        } while (acceptOp(","));
        List<PhpClassTemplate.TraitPrecedence> precedences = new ArrayList<>();
        List<PhpClassTemplate.TraitAlias> aliases = new ArrayList<>();
        if (acceptOp("{")) {
            while (!acceptOp("}")) {
                if (peek().kind() == Kind.EOF) {
                    throw error("syntax error, unexpected end of file in trait use");
                }
                String first = expectIdent().text();
                String traitName = null;
                String methodName;
                if (acceptOp("::")) {
                    traitName = resolveClassName(first);
                    methodName = expectIdent().text();
                } else {
                    methodName = first;
                }
                if (traitName != null && acceptKeyword("insteadof")) {
                    List<String> excluded = new ArrayList<>();
                    do {
                        excluded.add(resolveClassName(expectIdent().text()));
                    } while (acceptOp(","));
                    expectOp(";");
                    precedences.add(new PhpClassTemplate.TraitPrecedence(traitName, methodName, excluded));
                } else {
                    expectKeyword("as");
                    Visibility vis = null;
                    if (acceptKeyword("public")) {
                        vis = Visibility.PUBLIC;
                    } else if (acceptKeyword("protected")) {
                        vis = Visibility.PROTECTED;
                    } else if (acceptKeyword("private")) {
                        vis = Visibility.PRIVATE;
                    }
                    String alias = peek().kind() == Kind.IDENT ? next().text() : null;
                    expectOp(";");
                    aliases.add(new PhpClassTemplate.TraitAlias(traitName, methodName, alias, vis));
                }
            }
        } else {
            expectOp(";");
        }
        return new PhpClassTemplate.TraitUse(traitNames, precedences, aliases);
    }

    private PhpClassTemplate.MethodDecl parseMethod(Visibility vis, boolean isStatic, boolean isAbstract, boolean isFinal,
                                                    boolean classReadonly, List<PhpClassTemplate.PropertyDecl> classProps) {
        boolean returnsRef = acceptOp("&");
        Token nameTok = expectIdent();
        String mName = nameTok.text();
        String lower = dev.phpruntime.runtime.PhpStrings.lower(mName);
        boolean isCtor = "__construct".equals(lower);
        Scope outerScope = scope;
        String outerFunction = currentFunction;
        boolean outerReturnsRef = currentReturnsRef;
        scope = currentIsTrait ? Scope.forTraitMethod(language) : Scope.forMethod(language);
        currentFunction = mName;
        currentReturnsRef = returnsRef;
        try {
            ParsedParams params = parseParams(isCtor && !isAbstract);
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
                scope.validateGotos();
                if (!params.promoted().isEmpty()) {
                    List<PhpNode> initStmts = new ArrayList<>(params.promoted().size() + 1);
                    for (PromotedProp pp : params.promoted()) {
                        boolean propRo = pp.isReadonly() || classReadonly;
                        classProps.add(new PhpClassTemplate.PropertyDecl(
                                pp.name(), pp.visibility(), false, propRo, PhpNull.INSTANCE, null));
                        LValueNode thisVar = scope.variable("this").at(pp.line());
                        LValueNode propLv = new PropertyLValueNode(thisVar, pp.name(), null, currentClassLower(),
                                scope.traitSelfSlot()).at(pp.line());
                        LValueNode paramVar = scope.variable(pp.name()).at(pp.line());
                        if (pp.byRef() && !propRo) {
                            initStmts.add(new AssignRefNode(propLv, paramVar).at(pp.line()));
                        } else {
                            initStmts.add(new AssignNode(propLv, paramVar).at(pp.line()));
                        }
                    }
                    initStmts.add(body);
                    body = block(initStmts);
                }
                PhpFunctionRootNode root = DynamicScopeLowering.finish(scope.isGenerator() ? body : null,
                        new PhpFunctionRootNode(
                        language, scope.build(), source, nameTok.line(),
                        currentClass, mName, isStatic, returnsRef, scope.thisSlot(), scope.calledClassSlot(), -1,
                        scope.traitSelfSlot(), new int[0], new VarKind[0], new boolean[0],
                        params.names(), params.slots(), params.kinds(), params.byRef(),
                        params.defaults(), params.variadic(), body));
                if (scope.isGenerator()) {
                    root.asGenerator();
                }
                callTarget = dev.phpruntime.bytecode.BytecodeEmitter.target(language, root);
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
                || peek(1).isOp(";") || peek(1).isOp("=>"))) {
            next();
            if (!acceptOp("|") && !(peek().isOp("&") && peek(1).kind() == Kind.IDENT && next() != null)) {
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
            return switch (dev.phpruntime.runtime.PhpStrings.lower(t.text())) {
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
            String text = op.kind() == Kind.IDENT ? dev.phpruntime.runtime.PhpStrings.lower(op.text()) : op.text();
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
            case "+" -> new AddNode(l, r);
            case "-" -> new SubNode(l, r);
            case "*" -> new MulNode(l, r);
            case "/" -> new DivNode(l, r);
            case "%" -> new ModNode(l, r);
            case "." -> new ConcatNode(l, r);
            case "**" -> new ArithNode(PhpArith.Op.POW, l, r);
            case "&" -> new ArithNode(PhpArith.Op.BIT_AND, l, r);
            case "|" -> new ArithNode(PhpArith.Op.BIT_OR, l, r);
            case "^" -> new ArithNode(PhpArith.Op.BIT_XOR, l, r);
            case "<<" -> new ArithNode(PhpArith.Op.SHL, l, r);
            case ">>" -> new ArithNode(PhpArith.Op.SHR, l, r);
            case "<" -> new CompareNode(CompareNode.Kind.LT, l, r);
            case "<=" -> new CompareNode(CompareNode.Kind.LE, l, r);
            case ">" -> new CompareNode(CompareNode.Kind.GT, l, r);
            case ">=" -> new CompareNode(CompareNode.Kind.GE, l, r);
            case "<=>" -> new CompareNode(CompareNode.Kind.SPACESHIP, l, r);
            case "==" -> new EqualNode(false, l, r);
            case "!=", "<>" -> new EqualNode(true, l, r);
            case "===" -> new IdenticalNode(false, l, r);
            case "!==" -> new IdenticalNode(true, l, r);
            case "&&", "and" -> new AndNode(l, r);
            case "||", "or" -> new OrNode(l, r);
            case "xor" -> new IdenticalNode(true, new NotNode(new NotNode(l)), new NotNode(new NotNode(r)));
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
                    return new NegNode(operand).at(line);
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
            String kw = dev.phpruntime.runtime.PhpStrings.lower(t.text());
            if (kw.equals("yield")) {
                next();
                if (scope.isGlobal()) {
                    throw error("The \"yield\" expression can only be used inside a function");
                }
                scope.generatorSlot();
                if (acceptKeyword("from")) {
                    if (currentReturnsRef) {
                        throw error("Cannot use \"yield from\" inside a by-reference generator");
                    }
                    return new dev.phpruntime.nodes.generator.YieldFromNode(binary(4)).at(line);
                }
                PhpNode key = null;
                PhpNode value = null;
                if (!peek().isOp(";") && !peek().isOp(")") && !peek().isOp("]") && !peek().isOp(",")
                        && !peek().isOp("}") && !peek().isOp(":")) {
                    value = binary(4);
                    if (acceptOp("=>")) {
                        key = value;
                        value = binary(4);
                    }
                }
                return new dev.phpruntime.nodes.generator.YieldNode(key, value,
                        currentReturnsRef).at(line);
            }
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
                int tableSlot = scope.dynamicSlot();
                return new IncludeNode(inc, expression(), tableSlot).at(line);
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
        return switch (dev.phpruntime.runtime.PhpStrings.lower(peek(1).text())) {
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
        if (e instanceof DestructureLValueNode dest) {
            expectOp("=");
            PhpNode rhs = binary(4);
            return new DestructureAssignNode(dest, rhs, lvalueOrNull(rhs)).at(line);
        }
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
                    if (compound == PhpArith.Op.CONCAT) {
                        if (target instanceof LocalVarNode local) { local.kind().markAppendTarget(); }
                        return new StringAppendNode(target, value).at(line);
                    }
                    if (target instanceof LocalVarNode local && arithmetic(compound) != null) {
                        // $x += v is $x = $x + v for a plain variable, and that way it gets the
                        // typed operator nodes and an unboxed assignment.
                        return new AssignNode(target, makeBinary(arithmetic(compound), local.copyForRead(), value).at(line)).at(line);
                    }
                    if (target instanceof IncludedVarNode local && arithmetic(compound) != null) {
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
        if (acceptOp("$")) {
            int tableSlot = scope.dynamicSlot();
            e = new DynamicVarNode(variableNameOperand(), tableSlot).at(line);
        } else if (t.kind() == Kind.VARIABLE) {
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
                            e = new IndexLValueNode(language, elem, keys.subList(1, keys.size()).toArray(new PhpNode[0])).at(line);
                        }
                    } else {
                        e = new IndexLValueNode(language, lv, keys.toArray(new PhpNode[0])).at(line);
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

    private PhpNode variableNameOperand() {
        if (acceptOp("{")) {
            PhpNode expr = expression();
            expectOp("}");
            return expr;
        }
        if (acceptOp("$")) { return new DynamicVarNode(variableNameOperand(), scope.dynamicSlot()); }
        Token variable = next();
        if (variable.kind() != Kind.VARIABLE) { throw error("expected variable or {expression} after $"); }
        return scope.variable(variable.text()).at(variable.line());
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
                access = new PropertyLValueNode(nodeTarget, member, null, currentClassLower(), scope.traitSelfSlot()).at(line);
            }
        } else if (peek().kind() == Kind.VARIABLE) {
            Token vt = next();
            PhpNode nameExpr = scope.variable(vt.text()).at(vt.line());
            if (acceptOp("(")) {
                access = parseMemberCallableOrCall(nodeTarget, null, -1, nameExpr, line, nullsafe);
            } else {
                access = new PropertyLValueNode(nodeTarget, null, nameExpr, currentClassLower(), scope.traitSelfSlot()).at(line);
            }
        } else if (acceptOp("{")) {
            PhpNode nameExpr = expression();
            expectOp("}");
            if (acceptOp("(")) {
                access = parseMemberCallableOrCall(nodeTarget, null, -1, nameExpr, line, nullsafe);
            } else {
                access = new PropertyLValueNode(nodeTarget, null, nameExpr, currentClassLower(), scope.traitSelfSlot()).at(line);
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
            return new StaticPropertyLValueNode(
                    classRef, vt.text(), null, currentClassLower(), scope.traitSelfSlot()).at(line);
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
        String lower = dev.phpruntime.runtime.PhpStrings.lower(name);
        String cur = currentClass != null ? currentClass : "<global>";
        // The frame's self slot serves trait code, rebindable closures and code included into a
        // method's scope. A (non-trait) class declared in an included file parses its member
        // initializers in that file's scope: there, self is the declared class.
        boolean slotSelf = scope.traitSelfSlot() >= 0
                && !(currentClass != null && !currentIsTrait && scope.isFileScope());
        return switch (lower) {
            case "self" -> slotSelf
                    ? ClassRefNode.traitSelf(scope.traitSelfSlot())
                    : ClassRefNode.self(cur, language.classId(cur));
            case "parent" -> slotSelf
                    ? ClassRefNode.traitParent(scope.traitSelfSlot())
                    : ClassRefNode.parent(cur, language.classId(cur));
            case "static" -> ClassRefNode.staticRef(scope.calledClassSlot());
            default -> ClassRefNode.named(name, language.classId(name));
        };
    }

    private ClassRefNode parseClassRef(boolean allowDynamic) {
        Token t = peek();
        if (t.kind() == Kind.IDENT) {
            next();
            return makeClassRefFromName(resolveClassName(t.text()));
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
        boolean anonReadonly = false;
        if (peek().isKeyword("readonly") && peek(1).isKeyword("class")) {
            next();
            anonReadonly = true;
        }
        if (acceptKeyword("class")) {
            ParsedCallArgs pca = acceptOp("(") ? parseCallArgDetails()
                    : new ParsedCallArgs(new PhpNode[0], new LValueNode[0], null, null);
            long tid = language.nextTemplateId();
            String anonName = "class@anonymous\0" + magicFile() + ":" + line + "$" + tid;
            PhpClassTemplate tpl = parseClassTemplate(tid, anonName, false, false, false, false, anonReadonly, true);
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
                    if (isBracketDestructuring()) {
                        return parseDestructurePattern("]").at(line);
                    }
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
        String lower = dev.phpruntime.runtime.PhpStrings.lower(name);
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
            return parseStaticMember(makeClassRefFromName(resolveClassName(name)), line);
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
                if (scope.traitSelfSlot() >= 0) {
                    return new TraitClassNameNode(scope.traitSelfSlot()).at(line);
                }
                return new ConstNode(currentClass != null ? currentClass : "").at(line);
            }
            case "__trait__" -> {
                return new ConstNode(currentIsTrait && currentClass != null ? currentClass : "").at(line);
            }
            case "__method__" -> {
                String m = currentClass != null && !currentFunction.isEmpty()
                        ? currentClass + "::" + currentFunction
                        : currentFunction;
                return new ConstNode(m).at(line);
            }
            case "__namespace__" -> {
                return new ConstNode(currentNamespace).at(line);
            }
            case "array" -> {
                if (peek().isOp("(")) {
                    next();
                    return arrayLiteral(")").at(line);
                }
            }
            case "list" -> {
                if (peek().isOp("(")) {
                    next();
                    return parseDestructurePattern(")").at(line);
                }
            }
            case "isset" -> {
                expectOp("(");
                List<PhpNode> targets = new ArrayList<>();
                do {
                    PhpNode target = expression();
                    if (!(target instanceof LValueNode || target instanceof IndexReadNode)) {
                        throw error("Cannot use isset() on the result of an expression (you can use \"null !== expression\" instead)");
                    }
                    targets.add(target);
                } while (acceptOp(","));
                expectOp(")");
                return new IssetNode(targets.toArray(new PhpNode[0])).at(line);
            }
            case "eval" -> {
                expectOp("(");
                PhpNode code = expression();
                expectOp(")");
                return new EvalNode(code, scope.dynamicSlot()).at(line);
            }
            case "match" -> {
                if (peek().isOp("(")) {
                    return parseMatch(line);
                }
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
                        case "func_num_args" -> new FuncArgsNode(FuncArgsNode.Kind.NUM_ARGS, scope.argOffset(),
                                scope.paramNames(), scope.paramSlots(), scope.paramKinds(),
                                scope.normalParamCount(), null).at(line);
                        case "func_get_args" -> new FuncArgsNode(FuncArgsNode.Kind.GET_ARGS, scope.argOffset(),
                                scope.paramNames(), scope.paramSlots(), scope.paramKinds(),
                                scope.normalParamCount(), null).at(line);
                        default -> {
                            if (args.length != 1) {
                                throw error(name + "() expects exactly 1 argument, " + args.length + " given");
                            }
                            yield new FuncArgsNode(FuncArgsNode.Kind.GET_ARG, scope.argOffset(),
                                    scope.paramNames(), scope.paramSlots(), scope.paramKinds(),
                                    scope.normalParamCount(), args[0]).at(line);
                        }
                    };
                }
            }
            default -> {
            }
        }
        if (peek().isOp("(")) {
            next();
            ResolvedName rn = resolveFunctionName(name);
            if (consumeFirstClassCallableEllipsis()) {
                if (rn.fallback() == null) {
                    return FirstClassCallableNode.forFunction(rn.primary(), language.functionId(rn.primary())).at(line);
                }
                return FirstClassCallableNode.forFunction(
                        rn.primary(), language.functionId(rn.primary()),
                        rn.fallback(), language.functionId(rn.fallback())).at(line);
            }
            ParsedCallArgs pca = parseCallArgDetails();
            String builtinName = rn.fallback() == null ? rn.primary() : rn.fallback();
            if (builtinName.equalsIgnoreCase("extract") || builtinName.equalsIgnoreCase("get_defined_vars")) {
                scope.dynamicSlot();
            }
            if (builtinName.equalsIgnoreCase("compact") && (pca.names() != null || pca.spreads() != null)) {
                scope.dynamicSlot();
            }
            if (builtinName.equalsIgnoreCase("get_defined_vars") && pca.names() == null && pca.spreads() == null) {
                if (pca.args().length != 0) { throw error("get_defined_vars() expects exactly 0 arguments"); }
                return scopeBuiltinFallback(rn, pca, new GetDefinedVarsNode(scope.dynamicSlot()), line);
            }
            if (builtinName.equalsIgnoreCase("compact") && pca.names() == null && pca.spreads() == null) {
                if (pca.args().length == 0) { throw error("compact() expects at least 1 argument"); }
                PhpNode[] args = pca.args();
                LValueNode[] reads = new LValueNode[args.length];
                String[] names = new String[args.length];
                boolean dynamic = false;
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof ConstNode constant && constant.value() instanceof String varName) {
                        names[i] = varName;
                        reads[i] = scope.compactVariable(varName);
                    } else { dynamic = true; }
                }
                return scopeBuiltinFallback(rn, pca, new CompactNode(args, reads, names, dynamic ? scope.dynamicSlot() : -1), line);
            }
            if (builtinName.equalsIgnoreCase("extract") && pca.names() == null && pca.spreads() == null) {
                if (pca.args().length < 1 || pca.args().length > 3) { throw error("extract() expects 1 to 3 arguments"); }
                PhpNode[] args = pca.args();
                return scopeBuiltinFallback(rn, pca, new ExtractNode(args[0], pca.refs()[0], args.length > 1 ? args[1] : null,
                        args.length > 2 ? args[2] : null, scope.dynamicSlot()), line);
            }
            if (rn.fallback() == null) {
                return new CallNode(rn.primary(), language.functionId(rn.primary()), pca.args(), pca.refs(),
                        pca.names(), pca.spreads()).withSpl(language).at(line);
            }
            return new CallNode(rn.primary(), language.functionId(rn.primary()),
                    rn.fallback(), language.functionId(rn.fallback()), pca.args(), pca.refs(),
                    pca.names(), pca.spreads()).withSpl(language).at(line);
        }
        ResolvedName rn = resolveConstName(name);
        if (rn.fallback() == null) {
            if (rn.primary().equalsIgnoreCase("true")) {
                return new ConstNode(true).at(line);
            }
            if (rn.primary().equalsIgnoreCase("false")) {
                return new ConstNode(false).at(line);
            }
            if (rn.primary().equalsIgnoreCase("null")) {
                return new ConstNode(PhpNull.INSTANCE).at(line);
            }
            Object constant = PhpConstants.compileTime(rn.primary());
            if (constant != null) {
                return new ConstNode(constant).at(line);
            }
            return new ReadConstantNode(rn.primary()).at(line);
        }
        return new ReadConstantNode(rn.fallback(), rn.primary()).at(line);
    }

    private PhpNode scopeBuiltinFallback(ResolvedName name, ParsedCallArgs args, PhpNode builtin, int line) {
        if (name.fallback() == null) { return builtin.at(line); }
        // Each branch owns its AST. Arguments must not have two different parents.
        PhpNode call = new CallNode(name.primary(), language.functionId(name.primary()),
                java.util.Arrays.stream(args.args()).map(com.oracle.truffle.api.nodes.NodeUtil::cloneNode).toArray(PhpNode[]::new),
                java.util.Arrays.stream(args.refs()).map(n -> n == null ? null : com.oracle.truffle.api.nodes.NodeUtil.cloneNode(n)).toArray(LValueNode[]::new),
                args.names(), args.spreads()).at(line);
        return new ScopeFunctionFallbackNode(language.functionId(name.primary()), call, builtin.at(line)).at(line);
    }

    private PhpNode parseMatch(int line) {
        expectOp("(");
        PhpNode subject = expression();
        expectOp(")");
        expectOp("{");
        List<MatchNode.MatchArm> arms = new ArrayList<>();
        PhpNode defaultBody = null;
        while (!acceptOp("}")) {
            if (peek().kind() == Kind.EOF) {
                throw error("syntax error, unexpected end of file in match");
            }
            if (acceptKeyword("default")) {
                if (defaultBody != null) {
                    throw error("Match expressions may only contain one default arm");
                }
                acceptOp(",");
                expectOp("=>");
                defaultBody = expression();
            } else {
                List<PhpNode> conds = new ArrayList<>();
                do {
                    conds.add(expression());
                } while (acceptOp(",") && !peek().isOp("=>"));
                expectOp("=>");
                PhpNode body = expression();
                arms.add(new MatchNode.MatchArm(conds.toArray(new PhpNode[0]), body));
            }
            if (!peek().isOp("}")) {
                expectOp(",");
            }
        }
        return new MatchNode(subject, arms.toArray(new MatchNode.MatchArm[0]), defaultBody).at(line);
    }

    private String magicFile() {
        String path = source.getPath();
        if (path == null || path.isEmpty()) {
            return "";
        }
        return dev.phpruntime.runtime.PhpStrings.fromJava(Path.of(path).toAbsolutePath().normalize().toString());
    }

    private String magicDir() {
        String file = magicFile();
        if (file.isEmpty()) {
            return "";
        }
        Path parent = Path.of(dev.phpruntime.runtime.PhpStrings.toJava(file)).getParent();
        return parent == null ? "" : dev.phpruntime.runtime.PhpStrings.fromJava(parent.toString());
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

    private boolean isBracketDestructuringAt(int bracketPos) {
        int depth = 1;
        int i = bracketPos + 1;
        while (i < tokens.size() && depth > 0) {
            Token tok = tokens.get(i++);
            if (tok.kind() == Kind.EOF) {
                return false;
            }
            if (tok.isOp("[") || tok.isOp("#[")) {
                depth++;
            } else if (tok.isOp("]")) {
                depth--;
            }
        }
        return depth == 0 && i < tokens.size() && tokens.get(i).isOp("=>");
    }

    private boolean isBracketDestructuring() {
        int depth = 1;
        int i = pos;
        while (i < tokens.size() && depth > 0) {
            Token tok = tokens.get(i++);
            if (tok.kind() == Kind.EOF) {
                return false;
            }
            if (tok.isOp("[") || tok.isOp("#[")) {
                depth++;
            } else if (tok.isOp("]")) {
                depth--;
            }
        }
        return depth == 0 && i < tokens.size() && tokens.get(i).isOp("=");
    }

    private DestructureLValueNode parseDestructurePattern(String closing) {
        List<PhpNode> keyNodes = new ArrayList<>();
        List<LValueNode> targets = new ArrayList<>();
        List<Long> autoIndices = new ArrayList<>();
        List<Boolean> byRefs = new ArrayList<>();
        Boolean keyedMode = null;
        long nextIndex = 0;

        while (!acceptOp(closing)) {
            if (acceptOp(",")) {
                if (Boolean.TRUE.equals(keyedMode)) {
                    throw error("Cannot use empty array entries in keyed array assignment");
                }
                keyedMode = false;
                keyNodes.add(null);
                targets.add(null);
                autoIndices.add(nextIndex++);
                byRefs.add(false);
                continue;
            }
            if (acceptOp("&")) {
                if (Boolean.TRUE.equals(keyedMode)) {
                    throw error("Cannot mix keyed and unkeyed array entries in assignments");
                }
                keyedMode = false;
                LValueNode tgt = parseDestructureItem(true);
                keyNodes.add(null);
                targets.add(tgt);
                autoIndices.add(nextIndex++);
                byRefs.add(true);
            } else if (peek().isOp("[")) {
                int saved = pos;
                if (isBracketDestructuringAt(pos)) {
                    PhpNode keyExpr = expression();
                    expectOp("=>");
                    if (Boolean.FALSE.equals(keyedMode)) {
                        throw error("Cannot mix keyed and unkeyed array entries in assignments");
                    }
                    keyedMode = true;
                    boolean isRef = acceptOp("&");
                    LValueNode tgt = parseDestructureValueAfterArrow(isRef);
                    keyNodes.add(keyExpr);
                    targets.add(tgt);
                    autoIndices.add(-1L);
                    byRefs.add(isRef);
                } else {
                    int subLine = next().line();
                    if (Boolean.TRUE.equals(keyedMode)) {
                        throw error("Cannot mix keyed and unkeyed array entries in assignments");
                    }
                    keyedMode = false;
                    keyNodes.add(null);
                    targets.add(parseDestructurePattern("]").at(subLine));
                    autoIndices.add(nextIndex++);
                    byRefs.add(false);
                }
            } else if (peek().isKeyword("list") && peek(1).isOp("(")) {
                int subLine = next().line();
                next();
                if (Boolean.TRUE.equals(keyedMode)) {
                    throw error("Cannot mix keyed and unkeyed array entries in assignments");
                }
                keyedMode = false;
                keyNodes.add(null);
                targets.add(parseDestructurePattern(")").at(subLine));
                autoIndices.add(nextIndex++);
                byRefs.add(false);
            } else {
                PhpNode first = expression();
                if (acceptOp("=>")) {
                    if (Boolean.FALSE.equals(keyedMode)) {
                        throw error("Cannot mix keyed and unkeyed array entries in assignments");
                    }
                    keyedMode = true;
                    boolean isRef = acceptOp("&");
                    LValueNode tgt = parseDestructureValueAfterArrow(isRef);
                    keyNodes.add(first);
                    targets.add(tgt);
                    autoIndices.add(-1L);
                    byRefs.add(isRef);
                } else {
                    if (Boolean.TRUE.equals(keyedMode)) {
                        throw error("Cannot mix keyed and unkeyed array entries in assignments");
                    }
                    keyedMode = false;
                    LValueNode tgt = lvalue(first);
                    keyNodes.add(null);
                    targets.add(tgt);
                    autoIndices.add(nextIndex++);
                    byRefs.add(false);
                }
            }
            if (!peek().isOp(closing)) {
                expectOp(",");
            }
        }
        long[] idxArr = new long[autoIndices.size()];
        boolean[] refArr = new boolean[byRefs.size()];
        for (int i = 0; i < autoIndices.size(); i++) {
            idxArr[i] = autoIndices.get(i);
            refArr[i] = byRefs.get(i);
        }
        return new DestructureLValueNode(
                keyNodes.toArray(new PhpNode[0]),
                targets.toArray(new LValueNode[0]),
                idxArr,
                refArr);
    }

    private LValueNode parseDestructureValueAfterArrow(boolean isRef) {
        if (!isRef) {
            if (peek().isOp("[")) {
                int subLine = next().line();
                return parseDestructurePattern("]").at(subLine);
            }
            if (peek().isKeyword("list") && peek(1).isOp("(")) {
                int subLine = next().line();
                next();
                return parseDestructurePattern(")").at(subLine);
            }
        }
        return parseDestructureItem(isRef);
    }

    private LValueNode parseDestructureItem(boolean isRef) {
        LValueNode lv = lvalue(postfixOnly());
        if (isRef && lv instanceof LocalVarNode local && !"this".equals(local.varName())) {
            local.kind().markCell();
        }
        return lv;
    }

    private static void markCellIfLocal(PhpNode node) {
        if (node instanceof LocalVarNode lv && !"this".equals(lv.varName())) {
            lv.kind().markCell();
        }
    }

    private PhpNode interpolation(Token t) {
        List<PhpNode> parts = new ArrayList<>();
        for (Token.Part part : t.parts()) {
            if (part.kind() == Token.PartKind.LITERAL) {
                parts.add(new ConstNode(part.value()));
                continue;
            }
            List<Token> sub = Lexer.tokenizeCode(part.value());
            Parser p = new Parser(language, source, sub, scope, functions, topLevelClasses,
                    currentFunction, currentClass, currentIsTrait, currentNamespace,
                    classAliases, functionAliases, constAliases);
            PhpNode e = p.expression();
            if (p.peek().kind() != Kind.EOF) {
                throw new PhpParseError("syntax error in string interpolation", t.line());
            }
            if (part.kind() == Token.PartKind.DYNAMIC_VAR) {
                e = new DynamicVarNode(e, scope.dynamicSlot()).at(t.line());
            }
            parts.add(e);
        }
        return new InterpolateNode(parts.toArray(new PhpNode[0]));
    }

    // =================================================================== namespaces & imports

    private enum UseKind { CLASS, FUNCTION, CONST }

    private record ResolvedName(String primary, String fallback) {}

    private PhpNode namespaceStatement() {
        if (acceptOp("{")) {
            enterNamespace("");
            List<PhpNode> stmts = parseTopLevelBlock();
            exitNamespace();
            return stmts.isEmpty() ? null : block(stmts);
        }
        String ns = stripLeadingBackslash(expectIdent().text());
        if (ns.endsWith("\\")) {
            ns = ns.substring(0, ns.length() - 1);
        }
        enterNamespace(ns);
        if (acceptOp("{")) {
            List<PhpNode> stmts = parseTopLevelBlock();
            exitNamespace();
            return stmts.isEmpty() ? null : block(stmts);
        }
        endStatement();
        return null;
    }

    private List<PhpNode> parseTopLevelBlock() {
        List<PhpNode> stmts = new ArrayList<>();
        while (!acceptOp("}")) {
            if (peek().kind() == Kind.EOF) {
                throw error("syntax error, unexpected end of file");
            }
            PhpNode s = statement(true);
            if (s != null) {
                stmts.add(s);
            }
        }
        return stmts;
    }

    private void enterNamespace(String ns) {
        currentNamespace = ns;
        classAliases.clear();
        functionAliases.clear();
        constAliases.clear();
    }

    private void exitNamespace() {
        enterNamespace("");
    }

    private void parseUseStatement() {
        UseKind defaultKind = UseKind.CLASS;
        if (acceptKeyword("function")) {
            defaultKind = UseKind.FUNCTION;
        } else if (acceptKeyword("const")) {
            defaultKind = UseKind.CONST;
        }
        do {
            String raw = stripLeadingBackslash(expectIdent().text());
            if (acceptOp("{")) {
                String prefix = raw.endsWith("\\") ? raw : raw + "\\";
                while (!acceptOp("}")) {
                    if (peek().kind() == Kind.EOF) {
                        throw error("syntax error, unexpected end of file");
                    }
                    UseKind itemKind = defaultKind;
                    if (defaultKind == UseKind.CLASS) {
                        if (acceptKeyword("function")) {
                            itemKind = UseKind.FUNCTION;
                        } else if (acceptKeyword("const")) {
                            itemKind = UseKind.CONST;
                        }
                    }
                    String sub = stripLeadingBackslash(expectIdent().text());
                    String fqn = prefix + sub;
                    String alias = acceptKeyword("as") ? expectIdent().text() : lastSegment(sub);
                    registerUse(itemKind, fqn, alias);
                    if (!peek().isOp("}")) {
                        expectOp(",");
                    }
                }
            } else {
                String alias = acceptKeyword("as") ? expectIdent().text() : lastSegment(raw);
                registerUse(defaultKind, raw, alias);
            }
        } while (acceptOp(","));
        endStatement();
    }

    private void registerUse(UseKind kind, String fqn, String alias) {
        switch (kind) {
            case CLASS -> classAliases.put(dev.phpruntime.runtime.PhpStrings.lower(alias), fqn);
            case FUNCTION -> functionAliases.put(dev.phpruntime.runtime.PhpStrings.lower(alias), fqn);
            case CONST -> constAliases.put(alias, fqn);
        }
    }

    private static String stripLeadingBackslash(String s) {
        return s.startsWith("\\") ? s.substring(1) : s;
    }

    private static String lastSegment(String name) {
        int sep = name.lastIndexOf('\\');
        return sep < 0 ? name : name.substring(sep + 1);
    }

    private String qualifyDeclaration(String name) {
        return currentNamespace.isEmpty() ? name : currentNamespace + "\\" + name;
    }

    private String resolveRelative(String name) {
        String rest = name.substring("namespace\\".length());
        return currentNamespace.isEmpty() ? rest : currentNamespace + "\\" + rest;
    }

    private String resolveClassName(String raw) {
        if (raw.startsWith("\\")) {
            return raw.substring(1);
        }
        String lower = dev.phpruntime.runtime.PhpStrings.lower(raw);
        if (lower.startsWith("namespace\\")) {
            return resolveRelative(raw);
        }
        if (lower.equals("self") || lower.equals("parent") || lower.equals("static")) {
            return raw;
        }
        int sep = raw.indexOf('\\');
        if (sep < 0) {
            String aliased = classAliases.get(lower);
            return aliased != null ? aliased : qualifyDeclaration(raw);
        }
        String first = dev.phpruntime.runtime.PhpStrings.lower(raw.substring(0, sep));
        String rest = raw.substring(sep + 1);
        String aliased = classAliases.get(first);
        return aliased != null ? aliased + "\\" + rest : qualifyDeclaration(raw);
    }

    private ResolvedName resolveFunctionName(String raw) {
        if (raw.startsWith("\\")) {
            return new ResolvedName(raw.substring(1), null);
        }
        if (dev.phpruntime.runtime.PhpStrings.lower(raw).startsWith("namespace\\")) {
            return new ResolvedName(resolveRelative(raw), null);
        }
        int sep = raw.indexOf('\\');
        if (sep < 0) {
            String aliased = functionAliases.get(dev.phpruntime.runtime.PhpStrings.lower(raw));
            if (aliased != null) {
                return new ResolvedName(aliased, null);
            }
            if (currentNamespace.isEmpty()) {
                return new ResolvedName(raw, null);
            }
            return new ResolvedName(currentNamespace + "\\" + raw, raw);
        }
        String first = dev.phpruntime.runtime.PhpStrings.lower(raw.substring(0, sep));
        String rest = raw.substring(sep + 1);
        String aliased = classAliases.get(first);
        String fqn = aliased != null ? aliased + "\\" + rest : qualifyDeclaration(raw);
        return new ResolvedName(fqn, null);
    }

    private ResolvedName resolveConstName(String raw) {
        if (raw.startsWith("\\")) {
            return new ResolvedName(raw.substring(1), null);
        }
        if (dev.phpruntime.runtime.PhpStrings.lower(raw).startsWith("namespace\\")) {
            return new ResolvedName(resolveRelative(raw), null);
        }
        int sep = raw.indexOf('\\');
        if (sep < 0) {
            String aliased = constAliases.get(raw);
            if (aliased != null) {
                return new ResolvedName(aliased, null);
            }
            if (currentNamespace.isEmpty()) {
                return new ResolvedName(raw, null);
            }
            return new ResolvedName(currentNamespace + "\\" + raw, raw);
        }
        String first = dev.phpruntime.runtime.PhpStrings.lower(raw.substring(0, sep));
        String rest = raw.substring(sep + 1);
        String aliased = classAliases.get(first);
        String fqn = aliased != null ? aliased + "\\" + rest : qualifyDeclaration(raw);
        return new ResolvedName(fqn, null);
    }
}
