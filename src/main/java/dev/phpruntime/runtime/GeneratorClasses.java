package dev.phpruntime.runtime;

import dev.phpruntime.PhpLanguage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared Generator template; its native methods resume per-request PhpGenerator frames. */
public final class GeneratorClasses {
    private GeneratorClasses() {
    }

    public static PhpClassTemplate buildTemplate(PhpLanguage language) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        for (String method : List.of("__construct", "current", "next", "key", "valid", "rewind", "send", "throw", "getReturn")) {
            String lower = method.toLowerCase();
            methods.put(lower, new PhpClassTemplate.MethodDecl(method, lower, language.methodId(method),
                    Visibility.PUBLIC, false, false, false,
                    new dev.phpruntime.nodes.generator.GeneratorMethodRootNode(language, method).getCallTarget()));
        }
        return new PhpClassTemplate(language.nextTemplateId(), "Generator", false,
                false, true, false, null, List.of("Iterator"), List.of(), List.of(), methods);
    }
}
