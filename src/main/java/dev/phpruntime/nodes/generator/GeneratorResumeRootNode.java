package dev.phpruntime.nodes.generator;

import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.ControlFlow;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.PhpRootNode;
import dev.phpruntime.runtime.PhpGenerator;
import dev.phpruntime.runtime.PhpNull;

/** Resumes the body on the generator's saved frame on the calling request's thread. */
public final class GeneratorResumeRootNode extends PhpRootNode {
    public final PhpFunctionRootNode signature;
    @Child private PhpNode body;

    public GeneratorResumeRootNode(PhpLanguage language, FrameDescriptor descriptor,
                                   PhpFunctionRootNode signature, PhpNode body) {
        super(language, descriptor, signature.source(), signature.getSourceSection().getStartLine());
        this.signature = signature;
        this.body = body;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (dev.phpruntime.bytecode.BytecodeEmitter.ALL) {
            throw dev.phpruntime.bytecode.BytecodeEmitter.astExecuted(this);
        }
        TruffleSafepoint.poll(this);
        PhpGenerator generator = (PhpGenerator) frame.getArguments()[0];
        try {
            body.executeVoid(generator.frame);
            return PhpNull.INSTANCE;
        } catch (ControlFlow.Return result) {
            return result.value;
        }
    }

    @Override
    public String getName() {
        return signature.getName();
    }
}
