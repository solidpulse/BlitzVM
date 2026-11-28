package dev.phpruntime.builtins;

import dev.phpruntime.PhpLanguage;
import dev.phpruntime.runtime.PhpClassTemplate;
import dev.phpruntime.runtime.PhpParameter;
import dev.phpruntime.runtime.PhpSignature;
import dev.phpruntime.runtime.Visibility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The session extension's handler interfaces. The engine has no session module of its own. */
public final class SessionClasses {
    private SessionClasses() {
    }

    public static void buildTemplates(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        Map<String, String[]> id = new LinkedHashMap<>();
        id.put("create_sid", new String[0]);
        interfaceTemplate(language, out, "SessionIdInterface", id);

        Map<String, String[]> update = new LinkedHashMap<>();
        update.put("validateId", new String[]{"id"});
        update.put("updateTimestamp", new String[]{"id", "data"});
        interfaceTemplate(language, out, "SessionUpdateTimestampHandlerInterface", update);

        Map<String, String[]> handler = new LinkedHashMap<>();
        handler.put("open", new String[]{"path", "name"});
        handler.put("close", new String[0]);
        handler.put("read", new String[]{"id"});
        handler.put("write", new String[]{"id", "data"});
        handler.put("destroy", new String[]{"id"});
        handler.put("gc", new String[]{"max_lifetime"});
        interfaceTemplate(language, out, "SessionHandlerInterface", handler);
    }

    private static void interfaceTemplate(PhpLanguage language, Map<String, PhpClassTemplate> out,
                                          String name, Map<String, String[]> methods) {
        Map<String, PhpClassTemplate.MethodDecl> declarations = new LinkedHashMap<>();
        Map<String, PhpClassTemplate.MethodSignature> signatures = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> method : methods.entrySet()) {
            String lower = method.getKey().toLowerCase();
            List<PhpParameter> parameters = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (String parameter : method.getValue()) {
                parameters.add(new PhpParameter(parameter, null, List.of(), false, false, false, null, null));
                names.add(parameter);
            }
            PhpSignature signature = new PhpSignature(parameters, null, List.of());
            declarations.put(lower, new PhpClassTemplate.MethodDecl(method.getKey(), lower,
                    language.methodId(method.getKey()), Visibility.PUBLIC, false, true, false, null, signature));
            signatures.put(lower, new PhpClassTemplate.MethodSignature(names,
                    Collections.nCopies(names.size(), "mixed"), null, false));
        }
        PhpClassTemplate template = new PhpClassTemplate(language.nextTemplateId(), name, true,
                false, false, false, false, null, List.of(), List.of(), List.of(), List.of(),
                declarations, signatures);
        out.put(template.lowerName(), template);
    }
}
