package dev.phpruntime.runtime;

import dev.phpruntime.PhpLanguage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared SPL class templates; all mutable native state belongs to a request's PhpObject. */
public final class SplClasses {
    private SplClasses() {
    }

    public static Map<String, PhpClassTemplate> buildTemplates(PhpLanguage language) {
        Map<String, PhpClassTemplate> out = new LinkedHashMap<>();
        iface(language, out, "Traversable", List.of());
        iface(language, out, "Iterator", List.of("Traversable"), "current", "next", "key", "valid", "rewind");
        iface(language, out, "IteratorAggregate", List.of("Traversable"), "getIterator");
        iface(language, out, "ArrayAccess", List.of(), "offsetExists", "offsetGet", "offsetSet", "offsetUnset");
        iface(language, out, "Countable", List.of(), "count");
        iface(language, out, "JsonSerializable", List.of(), "jsonSerialize");
        iface(language, out, "Stringable", List.of(), "__toString");
        iface(language, out, "Serializable", List.of(), "serialize", "unserialize");
        iface(language, out, "SeekableIterator", List.of("Iterator"), "seek");
        iface(language, out, "RecursiveIterator", List.of("Iterator"), "hasChildren", "getChildren");
        iface(language, out, "OuterIterator", List.of("Iterator"), "getInnerIterator");
        return out;
    }

    private static void iface(PhpLanguage language, Map<String, PhpClassTemplate> out,
                              String name, List<String> parents, String... methods) {
        Map<String, PhpClassTemplate.MethodDecl> declarations = new LinkedHashMap<>();
        for (String method : methods) {
            String lower = method.toLowerCase();
            declarations.put(lower, new PhpClassTemplate.MethodDecl(method, lower, language.methodId(method),
                    Visibility.PUBLIC, false, true, false, null));
        }
        PhpClassTemplate template = new PhpClassTemplate(language.nextTemplateId(), name, true,
                false, false, false, null, parents, List.of(), List.of(), declarations);
        out.put(template.lowerName(), template);
    }
}
