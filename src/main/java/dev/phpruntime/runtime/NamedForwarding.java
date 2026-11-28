package dev.phpruntime.runtime;

/**
 * The Java-implemented functions and methods php lets accept named arguments it does not
 * declare, and forwards to the callable or constructor they invoke. Other builtins and
 * methods reject an unknown name, as php does.
 */
public final class NamedForwarding {

    private NamedForwarding() {
    }

    /** Whether a builtin forwards undeclared named arguments to its callback. */
    public static boolean forwardsFunction(String name) {
        return "call_user_func".equalsIgnoreCase(name);
    }

    /** Whether a Java-implemented method forwards undeclared named arguments. */
    public static boolean forwardsMethod(PhpClass.MethodEntry entry) {
        if (entry == null || entry.declaringClass() == null) {
            return false;
        }
        String cls = entry.declaringClass().template().lowerName();
        String method = PhpStrings.lower(entry.name());
        if (method.equals("invoke")) {
            return cls.equals("reflectionfunction") || cls.equals("reflectionmethod");
        }
        if (method.equals("newinstance")) {
            // ReflectionObject and ReflectionEnum inherit ReflectionClass::newInstance.
            return cls.equals("reflectionclass");
        }
        if (method.equals("call")) {
            return cls.equals("closure");
        }
        return false;
    }
}
