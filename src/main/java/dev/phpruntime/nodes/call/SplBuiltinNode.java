package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.stmt.IteratorNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.SplInterfaces;

/** Inlineable native function hooks; no boundary between count/iteration and user methods. */
public final class SplBuiltinNode extends Node {
    private final String name;
    @Child private ObjectMethodNode count;
    @Child private IteratorNode iterator;

    public SplBuiltinNode(PhpLanguage language, String name) {
        this.name = name;
        count = new ObjectMethodNode(language, "count");
        iterator = new IteratorNode(language);
    }

    public static boolean supports(String name) {
        return name.equals("count") || name.equals("sizeof") || name.equals("iterator_to_array")
                || name.equals("iterator_count");
    }

    public Object execute(Object[] args) {
        Object subject = args[0];
        if (name.equals("count") || name.equals("sizeof")) {
            long mode = args.length > 1 ? args[1] instanceof Long n ? n : PhpOps.toLong(args[1]) : 0;
            if (mode != 0 && mode != 1) {
                throw new PhpError(PhpError.VALUE_ERROR, name + "(): Argument #2 ($mode) must be either COUNT_NORMAL or COUNT_RECURSIVE", this);
            }
            if (subject instanceof PhpArray array) {
                return mode == 1 ? countRecursive(array) : (long) array.size();
            }
            if (subject instanceof PhpObject obj && obj.phpClass().hasSplInterface(SplInterfaces.COUNTABLE)) {
                Object value = CallArgHelper.unwrapReturn(count.call(obj));
                return value instanceof Long n ? n : PhpOps.toLong(value);
            }
            throw new PhpError(PhpError.TYPE_ERROR, name + "(): Argument #1 ($value) must be of type Countable|array, "
                    + PhpOps.typeDescription(subject) + " given", this);
        }
        boolean preserve = args.length < 2 || PhpOps.toBool(args[1]);
        if (subject instanceof PhpArray array) {
            if (name.equals("iterator_count")) {
                return (long) array.size();
            }
            if (preserve) {
                return array.share();
            }
            return PhpArray.ofList(array.values());
        }
        if (!(subject instanceof PhpObject obj) || !obj.phpClass().hasSplInterface(SplInterfaces.TRAVERSABLE)) {
            throw new PhpError(PhpError.TYPE_ERROR, name + "(): Argument #1 ($iterator) must be of type Traversable|array, "
                    + PhpOps.typeDescription(subject) + " given", this);
        }
        PhpObject it = iterator.resolve(obj);
        iterator.rewind(it);
        PhpArray result = new PhpArray();
        long size = 0;
        while (iterator.valid(it)) {
            TruffleSafepoint.poll(this);
            if (name.equals("iterator_to_array")) {
                Object value = PhpOps.copyOnAssign(iterator.current(it));
                if (preserve) {
                    result.put(iterator.key(it), value);
                } else {
                    result.append(value);
                }
            }
            size++;
            iterator.next(it);
        }
        return name.equals("iterator_count") ? size : result;
    }

    private static long countRecursive(PhpArray array) {
        long count = array.size();
        for (Object value : array.values()) {
            if (value instanceof PhpArray nested) {
                count += countRecursive(nested);
            }
        }
        return count;
    }
}
