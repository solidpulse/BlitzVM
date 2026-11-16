package dev.phpruntime.bc;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import dev.phpruntime.runtime.PhpStrings;

/** Isolated, deliberately incomplete language for the bytecode spike. */
@TruffleLanguage.Registration(id = BytecodeLanguage.ID, name = "PHP bytecode spike",
        characterMimeTypes = "application/x-php-bytecode", defaultMimeType = "application/x-php-bytecode")
public final class BytecodeLanguage extends TruffleLanguage<TruffleLanguage.Env> {
    public static final String ID = "php-bc";
    private static final ContextReference<Env> CONTEXT = ContextReference.create(BytecodeLanguage.class);

    static Env context(com.oracle.truffle.api.nodes.Node node) {
        return CONTEXT.get(node);
    }

    @Override
    protected Env createContext(Env env) {
        return env;
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        return new BytecodeParser(this, PhpStrings.fromJava(request.getSource().getCharacters().toString()))
                .parse().getCallTarget();
    }
}
