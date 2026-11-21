package dev.phpruntime;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.EventContext;
import com.oracle.truffle.api.instrumentation.ExecutionEventNode;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A test instrument that records every root entered with the name and source line a profiler
 * would show. The CPU sampler jar is not on the test classpath, so the test uses the Truffle
 * instrumentation API directly; a RootTag listener is what the sampler's own filter matches.
 */
@TruffleInstrument.Registration(id = RootProbeInstrument.ID, name = "PHP root probe", version = "1.0",
        services = RootProbeInstrument.Recorder.class)
public final class RootProbeInstrument extends TruffleInstrument {

    public static final String ID = "php-root-probe";

    /** One root entry: the qualified name, the source and the first line. */
    public record Root(String name, String source, int line) {
    }

    /** The roots the instrument has seen; read by the test through the polyglot Engine. */
    public static final class Recorder {
        private final List<Root> roots = Collections.synchronizedList(new ArrayList<>());

        void add(Root root) {
            roots.add(root);
        }

        public List<Root> roots() {
            synchronized (roots) {
                return List.copyOf(roots);
            }
        }
    }

    @Override
    protected void onCreate(Env env) {
        Recorder recorder = new Recorder();
        env.registerService(recorder);
        env.getInstrumenter().attachExecutionEventFactory(
                SourceSectionFilter.newBuilder().tagIs(StandardTags.RootTag.class).build(),
                context -> new ExecutionEventNode() {
                    @Override
                    protected void onEnter(VirtualFrame frame) {
                        recorder.add(rootOf(context));
                    }
                });
    }

    private static Root rootOf(EventContext context) {
        var node = context.getInstrumentedNode();
        var section = context.getInstrumentedSourceSection();
        var root = node.getRootNode();
        String name = root == null ? String.valueOf(node) : root.getQualifiedName();
        return new Root(name,
                section == null ? null : section.getSource().getName(),
                section == null ? -1 : section.getStartLine());
    }
}
