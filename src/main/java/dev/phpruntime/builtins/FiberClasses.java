package dev.phpruntime.builtins;

import dev.phpruntime.PhpLanguage;
import dev.phpruntime.runtime.PhpClassTemplate;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpExceptions;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.Visibility;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Fiber class. Only getCurrent() exists: this runtime runs guest code on the request
 * thread and never constructs a fiber, so it is always null. Full fiber scheduling is a
 * separate feature.
 */
public final class FiberClasses {
    private FiberClasses() {
    }

    public static void buildTemplates(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        methods.put("getcurrent", new PhpClassTemplate.MethodDecl("getCurrent", "getcurrent",
                language.methodId("getCurrent"), Visibility.PUBLIC, true, false, false,
                new PhpExceptions.BuiltinMethodRootNode(language, "Fiber::getCurrent",
                        (ctx, self, args) -> {
                            if (args.length != 0) {
                                throw new PhpError(PhpError.ARGUMENT_COUNT,
                                        "Fiber::getCurrent() expects exactly 0 arguments, " + args.length + " given", null);
                            }
                            return PhpNull.INSTANCE;
                        }).getCallTarget()));
        out.put("fiber", new PhpClassTemplate(language.nextTemplateId(), "Fiber", false, false,
                true, false, null, List.of(), List.of(), List.of(), methods));
    }
}
