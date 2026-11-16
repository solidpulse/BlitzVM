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
        out.put("generator", GeneratorClasses.buildTemplate(language));
        arrayClasses(language, out);
        iteratorClasses(language, out);
        fileClasses(language, out);
        Map<String, PhpClassTemplate.MethodDecl> storageMethods = new LinkedHashMap<>();
        for (String method : List.of("__construct", "attach", "detach", "contains", "offsetExists", "offsetGet", "offsetSet", "offsetUnset",
                "count", "rewind", "valid", "current", "key", "next", "getInfo", "setInfo", "getHash", "addAll", "removeAll", "removeAllExcept",
                "serialize", "unserialize")) {
            String lower = method.toLowerCase();
            storageMethods.put(lower, new PhpClassTemplate.MethodDecl(method, lower, language.methodId(method), Visibility.PUBLIC,
                    false, false, false, new dev.phpruntime.nodes.call.SplStorageMethodRootNode(language, method).getCallTarget()));
        }
        PhpClassTemplate storage = new PhpClassTemplate(language.nextTemplateId(), "SplObjectStorage", false,
                false, false, false, null, List.of("Countable", "Iterator", "Serializable", "ArrayAccess"),
                List.of(), List.of(), storageMethods);
        out.put(storage.lowerName(), storage);
        return out;
    }

    private static void iface(PhpLanguage language, Map<String, PhpClassTemplate> out,
                              String name, List<String> parents, String... methods) {
        Map<String, PhpClassTemplate.MethodDecl> declarations = new LinkedHashMap<>();
        Map<String, PhpClassTemplate.MethodSignature> signatures = new LinkedHashMap<>();
        for (String method : methods) {
            String lower = method.toLowerCase();
            declarations.put(lower, new PhpClassTemplate.MethodDecl(method, lower, language.methodId(method),
                    Visibility.PUBLIC, false, true, false, null));
            List<String> params = switch (lower) {
                case "offsetexists", "offsetget", "offsetunset" -> List.of("offset");
                case "offsetset" -> List.of("offset", "value");
                case "unserialize" -> List.of("data");
                case "seek" -> List.of("offset");
                default -> List.of();
            };
            List<String> types = lower.equals("unserialize") ? List.of("string") : lower.equals("seek") ? List.of("int")
                    : java.util.Collections.nCopies(params.size(), "mixed");
            String returnType = switch (lower) {
                case "current", "key", "offsetget", "jsonserialize" -> "mixed";
                case "valid", "offsetexists", "haschildren" -> "bool";
                case "count" -> "int";
                case "getiterator" -> "Traversable";
                case "getchildren" -> "?RecursiveIterator";
                case "getinneriterator" -> "?Iterator";
                case "__tostring" -> "string";
                case "serialize", "unserialize" -> null;
                default -> "void";
            };
            signatures.put(lower, new PhpClassTemplate.MethodSignature(params, types, returnType,
                    !name.equals("Stringable") && !name.equals("Serializable")));
        }
        PhpClassTemplate template = new PhpClassTemplate(language.nextTemplateId(), name, true,
                false, false, false, false, null, parents, List.of(), List.of(), List.of(), declarations, signatures);
        out.put(template.lowerName(), template);
    }

    private static void arrayClasses(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        for (String name : List.of("ArrayIterator", "ArrayObject")) {
            Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
            method(language, methods, name, "__construct", 0, name.equals("ArrayObject") ? 3 : 2);
            for (String method : List.of("count", "getArrayCopy", "getFlags")) {
                method(language, methods, name, method, 0, 0);
            }
            method(language, methods, name, "serialize", 0, 0);
            method(language, methods, name, "unserialize", 1, 1);
            for (String method : List.of("offsetExists", "offsetGet", "offsetUnset", "append", "setFlags", "uasort", "uksort")) {
                method(language, methods, name, method, 1, 1);
            }
            method(language, methods, name, "offsetSet", 2, 2);
            for (String method : List.of("asort", "ksort", "natsort", "natcasesort")) {
                method(language, methods, name, method, 0, method.endsWith("natsort") || method.equals("natcasesort") ? 0 : 1);
            }
            boolean aggregate = name.equals("ArrayObject");
            if (aggregate) {
                method(language, methods, name, "getIterator", 0, 0);
                method(language, methods, name, "exchangeArray", 1, 1);
                method(language, methods, name, "getIteratorClass", 0, 0);
                method(language, methods, name, "setIteratorClass", 1, 1);
            } else {
                for (String method : List.of("rewind", "valid", "key", "current", "next")) {
                    method(language, methods, name, method, 0, 0);
                }
                method(language, methods, name, "seek", 1, 1);
            }
            List<PhpClassTemplate.ConstantDecl> constants = List.of(constant("STD_PROP_LIST", 1), constant("ARRAY_AS_PROPS", 2));
            PhpClassTemplate template = new PhpClassTemplate(language.nextTemplateId(), name, false, false, false, false,
                    null, List.of(aggregate ? "IteratorAggregate" : "SeekableIterator", "ArrayAccess", "Serializable", "Countable"),
                    List.of(), constants, methods);
            out.put(template.lowerName(), template);
        }
        Map<String, PhpClassTemplate.MethodDecl> recursiveMethods = new LinkedHashMap<>();
        method(language, recursiveMethods, "RecursiveArrayIterator", "hasChildren", 0, 0);
        method(language, recursiveMethods, "RecursiveArrayIterator", "getChildren", 0, 0);
        PhpClassTemplate recursive = new PhpClassTemplate(language.nextTemplateId(), "RecursiveArrayIterator", false,
                false, false, false, "ArrayIterator", List.of("RecursiveIterator"), List.of(),
                List.of(constant("CHILD_ARRAYS_ONLY", 4)), recursiveMethods);
        out.put(recursive.lowerName(), recursive);
    }

    private static void iteratorClasses(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        for (String name : List.of("IteratorIterator", "RecursiveIteratorIterator")) {
            Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
            List<String> names = new java.util.ArrayList<>(List.of("__construct", "rewind", "valid", "current", "key", "next", "getInnerIterator"));
            if (name.equals("RecursiveIteratorIterator")) {
                names.addAll(List.of("getDepth", "setMaxDepth", "getMaxDepth", "getSubIterator", "callHasChildren",
                        "callGetChildren", "beginIteration", "endIteration", "beginChildren", "endChildren", "nextElement"));
            }
            for (String method : names) {
                String lower = method.toLowerCase();
                methods.put(lower, new PhpClassTemplate.MethodDecl(method, lower, language.methodId(method),
                        Visibility.PUBLIC, false, false, false,
                        new dev.phpruntime.nodes.call.SplIteratorMethodRootNode(language, name, method).getCallTarget()));
            }
            List<PhpClassTemplate.ConstantDecl> constants = name.equals("RecursiveIteratorIterator")
                    ? List.of(constant("LEAVES_ONLY", 0), constant("SELF_FIRST", 1), constant("CHILD_FIRST", 2), constant("CATCH_GET_CHILD", 16)) : List.of();
            PhpClassTemplate template = new PhpClassTemplate(language.nextTemplateId(), name, false, false, false,
                    false, null, List.of("OuterIterator"), List.of(), constants, methods);
            out.put(template.lowerName(), template);
        }
    }

    private static void fileClasses(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        for (String name : List.of("SplFileInfo", "DirectoryIterator", "FilesystemIterator", "RecursiveDirectoryIterator")) {
            List<String> names = new java.util.ArrayList<>(List.of("__construct"));
            String parent;
            List<String> interfaces;
            List<PhpClassTemplate.ConstantDecl> constants = List.of();
            switch (name) {
                case "SplFileInfo" -> {
                    parent = null;
                    interfaces = List.of("Stringable");
                    names.addAll(List.of("getPath", "getFilename", "getExtension", "getBasename", "getPathname", "getRealPath",
                            "getPerms", "getInode", "getSize", "getOwner", "getGroup", "getATime", "getMTime", "getCTime", "getType",
                            "isWritable", "isReadable", "isExecutable", "isFile", "isDir", "isLink", "getLinkTarget", "getFileInfo",
                            "getPathInfo", "setFileClass", "setInfoClass", "__toString"));
                }
                case "DirectoryIterator" -> {
                    parent = "SplFileInfo";
                    interfaces = List.of("SeekableIterator");
                    names.addAll(List.of("rewind", "next", "valid", "current", "key", "seek", "isDot", "__toString"));
                }
                default -> {
                    parent = name.equals("FilesystemIterator") ? "DirectoryIterator" : "FilesystemIterator";
                    interfaces = name.equals("RecursiveDirectoryIterator") ? List.of("RecursiveIterator") : List.of();
                    names.addAll(List.of("current", "key", "__toString", "getFlags", "setFlags"));
                    if (name.equals("RecursiveDirectoryIterator")) {
                        names.addAll(List.of("hasChildren", "getChildren", "getSubPath", "getSubPathname"));
                    }
                    constants = List.of(constant("CURRENT_MODE_MASK", 240), constant("CURRENT_AS_PATHNAME", 32),
                            constant("CURRENT_AS_FILEINFO", 0), constant("CURRENT_AS_SELF", 16), constant("KEY_MODE_MASK", 3840),
                            constant("KEY_AS_PATHNAME", 0), constant("KEY_AS_FILENAME", 256), constant("NEW_CURRENT_AND_KEY", 256),
                            constant("OTHER_MODE_MASK", 28672), constant("SKIP_DOTS", 4096), constant("UNIX_PATHS", 8192), constant("FOLLOW_SYMLINKS", 16384));
                }
            }
            Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
            for (String method : names) {
                String lower = method.toLowerCase();
                methods.put(lower, new PhpClassTemplate.MethodDecl(method, lower, language.methodId(method), Visibility.PUBLIC,
                        false, false, false, new dev.phpruntime.nodes.call.SplFileMethodRootNode(language, name, method).getCallTarget()));
            }
            PhpClassTemplate template = new PhpClassTemplate(language.nextTemplateId(), name, false, false, false,
                    false, parent, interfaces, List.of(), constants, methods);
            out.put(template.lowerName(), template);
        }
    }

    public static PhpClassTemplate.ConstantDecl constant(String name, long value) {
        return new PhpClassTemplate.ConstantDecl(name, Visibility.PUBLIC, false, value, null);
    }

    private static void method(PhpLanguage language, Map<String, PhpClassTemplate.MethodDecl> methods,
                               String owner, String name, int min, int max) {
        String lower = name.toLowerCase();
        methods.put(lower, new PhpClassTemplate.MethodDecl(name, lower, language.methodId(name), Visibility.PUBLIC,
                false, false, false, new dev.phpruntime.nodes.call.SplMethodRootNode(language, owner, name, min, max).getCallTarget()));
    }
}
