package dev.phpruntime.nodes.generator;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.call.DispatchCallNode;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpGenerator;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;

/** Native Generator methods deliberately have no TruffleBoundary: resume can inline into loops. */
public final class GeneratorMethodRootNode extends RootNode {
    private final String name;
    @Child private DispatchCallNode dispatch = new DispatchCallNode();

    public GeneratorMethodRootNode(PhpLanguage language, String name) {
        super(language);
        this.name = name;
    }

    @Override
    public boolean isCloningAllowed() {
        return true;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] args = frame.getArguments();
        if (name.equals("__construct")) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError("The \"Generator\" class is reserved for internal use and cannot be manually instantiated", this);
        }
        PhpGenerator generator = (PhpGenerator) ((PhpObject) args[0]).nativeState();
        int expected = name.equals("send") || name.equals("throw") ? 1 : 0;
        if (args.length - 2 != expected) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError(PhpError.ARGUMENT_COUNT, "Generator::" + name + "() expects exactly " + expected
                    + " argument" + (expected == 1 ? "" : "s") + ", " + (args.length - 2) + " given", this);
        }
        return switch (name) {
            case "current" -> generator.current(dispatch, this);
            case "key" -> generator.key(dispatch, this);
            case "valid" -> generator.valid(dispatch, this);
            case "next" -> { generator.next(dispatch, this); yield PhpNull.INSTANCE; }
            case "rewind" -> { generator.rewind(dispatch, this); yield PhpNull.INSTANCE; }
            case "send" -> {
                yield generator.send(args[2], dispatch, this);
            }
            case "throw" -> {
                if (args.length < 3 || !(args[2] instanceof PhpObject obj) || !obj.phpClass().isThrowable()) {
                    com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                    throw new PhpError(PhpError.TYPE_ERROR, "Generator::throw(): Argument #1 ($exception) must be of type Throwable", this);
                }
                yield generator.throwInto((PhpObject) args[2], dispatch, this);
            }
            case "getReturn" -> generator.getReturn(this);
            default -> throw new IllegalStateException(name);
        };
    }

    @Override
    public String getName() {
        return "Generator::" + name;
    }

}
