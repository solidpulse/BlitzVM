package dev.phpruntime.js;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import dev.phpruntime.js.parser.JsParser;

/**
 * The JavaScript front end: a Node-style subset (functions, arrows, let/const, numbers,
 * strings, templates, objects, async/await on host promises, module.exports handlers)
 * on the same scheduler, isolation model and HTTP server as PHP.
 */
@TruffleLanguage.Registration(
        id = JsLanguage.ID,
        name = "JavaScript (BlitzVM)",
        defaultMimeType = JsLanguage.MIME_TYPE,
        characterMimeTypes = JsLanguage.MIME_TYPE,
        contextPolicy = TruffleLanguage.ContextPolicy.SHARED)
public final class JsLanguage extends TruffleLanguage<JsContext> {

    public static final String ID = "blitzjs";
    public static final String MIME_TYPE = "application/x-blitzvm-javascript";

    @Override
    protected JsContext createContext(Env env) {
        return new JsContext(env);
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        return new JsParser(this, request.getSource()).parseModule().getCallTarget();
    }

    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        return singleThreaded;
    }
}
