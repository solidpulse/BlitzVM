package dev.phpruntime;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.source.Source;
import dev.phpruntime.nodes.PhpMainRootNode;
import dev.phpruntime.parser.Parser;

/**
 * Truffle entry point. {@link #parse} turns PHP source into a tree of self-specialising
 * AST nodes; Graal partially evaluates hot trees into machine code when running on a
 * JVMCI-enabled JDK, and the same tree simply interprets on a stock JDK.
 */
@TruffleLanguage.Registration(
        id = PhpLanguage.ID,
        name = "PHP",
        defaultMimeType = PhpLanguage.MIME_TYPE,
        characterMimeTypes = PhpLanguage.MIME_TYPE,
        fileTypeDetectors = PhpFileDetector.class,
        contextPolicy = TruffleLanguage.ContextPolicy.SHARED)
public final class PhpLanguage extends TruffleLanguage<PhpContext> {

    public static final String ID = "php";
    public static final String MIME_TYPE = "application/x-php";

    private static final LanguageReference<PhpLanguage> REFERENCE = LanguageReference.create(PhpLanguage.class);

    public static PhpLanguage get(com.oracle.truffle.api.nodes.Node node) {
        return REFERENCE.get(node);
    }

    @Override
    protected PhpContext createContext(Env env) {
        return new PhpContext(this, env);
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        Source source = request.getSource();
        PhpMainRootNode root = new Parser(this, source).parseMain();
        return root.getCallTarget();
    }

    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        // A PHP request is single-threaded, but the scheduler may resume it on another
        // carrier thread, so we allow any one thread at a time.
        return singleThreaded;
    }
}
