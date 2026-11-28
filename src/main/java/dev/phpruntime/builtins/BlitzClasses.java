package dev.phpruntime.builtins;

import com.oracle.truffle.api.object.DynamicObject;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.host.BlitzHttpRequest;
import dev.phpruntime.host.BlitzHttpResponseWriter;
import dev.phpruntime.host.BlitzRequestState;
import dev.phpruntime.host.BlitzResponseState;
import dev.phpruntime.host.BlitzServerRuntime;
import dev.phpruntime.host.BlitzServerState;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpCallable;
import dev.phpruntime.runtime.PhpClassTemplate;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpStrings;
import dev.phpruntime.runtime.Visibility;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The native {@code Blitz\Http\Server}, {@code Request} and {@code Response} PHP classes.
 * Their shape follows Swoole's HTTP API so the Swoole aliases can be thin class aliases later:
 * request properties are lowercase server/header arrays and {@code get}/{@code post}/{@code
 * cookie}/{@code files}; response methods are the Swoole set.
 */
public final class BlitzClasses {

    private static final String SERVER = "Blitz\\Http\\Server";
    private static final String REQUEST = "Blitz\\Http\\Request";
    private static final String RESPONSE = "Blitz\\Http\\Response";

    private static final DateTimeFormatter COOKIE_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
                    .withZone(ZoneOffset.UTC);

    private static final byte[] EMPTY = new byte[0];

    private BlitzClasses() {
    }

    public static void buildTemplates(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        buildServer(language, out);
        buildRequest(language, out);
        buildResponse(language, out);
    }

    private static void buildServer(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        addMethod(language, methods, "__construct", (ctx, self, args) -> {
            requireAtMost(SERVER + "::__construct", args, 4);
            requireAtLeast(SERVER + "::__construct", args, 1);
            String host = PhpOps.toStr(args[0]);
            long port = args.length > 1 ? PhpOps.toLong(args[1]) : 0;
            if (port < 0 || port > 65535) {
                throw new PhpError(PhpError.VALUE_ERROR,
                        SERVER + "::__construct(): Argument #2 ($port) must be between 0 and 65535", null);
            }
            Object services = ctx.serverHost();
            BlitzServerRuntime runtime = services instanceof BlitzServerRuntime r ? r : null;
            BlitzServerState state = new BlitzServerState(runtime, host, (int) port);
            state.setPhpObject(self);
            self.setNativeState(state);
            return PhpNull.INSTANCE;
        });
        addMethod(language, methods, "set", (ctx, self, args) -> {
            requireExactly(SERVER + "::set", args, 1);
            if (!(args[0] instanceof PhpArray options)) {
                throw typeError(SERVER + "::set", 1, "settings", "array", args[0]);
            }
            BlitzServerState state = serverState(self);
            for (var entry : options.entries()) {
                state.setOption(ctx, PhpOps.toStr(entry.getKey()), entry.getValue());
            }
            return true;
        });
        addMethod(language, methods, "on", (ctx, self, args) -> {
            requireExactly(SERVER + "::on", args, 2);
            String event = PhpOps.toStr(args[0]);
            PhpCallable callback = PhpCallable.resolve(ctx, args[1], ctx.currentBuiltinCallSite());
            serverState(self).on(ctx, event, callback);
            return true;
        });
        addMethod(language, methods, "start", (ctx, self, args) -> {
            requireExactly(SERVER + "::start", args, 0);
            serverState(self).start(ctx);
            return PhpNull.INSTANCE;
        });
        addMethod(language, methods, "stop", (ctx, self, args) -> {
            requireExactly(SERVER + "::stop", args, 0);
            serverState(self).stop();
            return true;
        });
        addMethod(language, methods, "getPort", (ctx, self, args) -> {
            requireExactly(SERVER + "::getPort", args, 0);
            return (long) serverState(self).port();
        });
        addMethod(language, methods, "getWorkerId", (ctx, self, args) -> {
            requireExactly(SERVER + "::getWorkerId", args, 0);
            return (long) serverState(self).workerId();
        });
        addMethod(language, methods, "getWorkerNum", (ctx, self, args) -> {
            requireExactly(SERVER + "::getWorkerNum", args, 0);
            return (long) serverState(self).workerNum();
        });
        out.put(SERVER.toLowerCase(Locale.ROOT), new PhpClassTemplate(
                language.nextTemplateId(), SERVER, false, false, false, false, null,
                java.util.List.of(), java.util.List.of(), java.util.List.of(), methods));
    }

    private static void buildRequest(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        addMethod(language, methods, "rawContent", (ctx, self, args) -> {
            requireExactly(REQUEST + "::rawContent", args, 0);
            return rawContent(requestState(self));
        });
        addMethod(language, methods, "getContent", (ctx, self, args) -> {
            requireExactly(REQUEST + "::getContent", args, 0);
            return rawContent(requestState(self));
        });
        addMethod(language, methods, "getMethod", (ctx, self, args) -> {
            requireExactly(REQUEST + "::getMethod", args, 0);
            return requestState(self).request.method();
        });
        addMethod(language, methods, "__get", (ctx, self, args) -> {
            requireExactly(REQUEST + "::__get", args, 1);
            String name = PhpOps.toStr(args[0]);
            Object value = requestProperty(self, name);
            if (value != null) {
                DynamicObject.PutNode.getUncached().execute(self, name, value);
            }
            return value == null ? PhpNull.INSTANCE : value;
        });
        addMethod(language, methods, "__isset", (ctx, self, args) -> {
            requireExactly(REQUEST + "::__isset", args, 1);
            return isRequestProperty(PhpOps.toStr(args[0]));
        });
        addMethod(language, methods, "__set", (ctx, self, args) -> {
            requireExactly(REQUEST + "::__set", args, 2);
            DynamicObject.PutNode.getUncached().execute(self, PhpOps.toStr(args[0]),
                    PhpOps.copyOnAssign(args[1]));
            return PhpNull.INSTANCE;
        });
        out.put(REQUEST.toLowerCase(Locale.ROOT), new PhpClassTemplate(
                language.nextTemplateId(), REQUEST, false, false, false, false, null,
                java.util.List.of(), java.util.List.of(), java.util.List.of(), methods));
    }

    private static void buildResponse(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();
        addMethod(language, methods, "status", (ctx, self, args) -> {
            requireAtLeast(RESPONSE + "::status", args, 1);
            requireAtMost(RESPONSE + "::status", args, 2);
            BlitzHttpResponseWriter writer = responseState(self).writer;
            writer.setStatus((int) PhpOps.toLong(args[0]),
                    args.length > 1 ? PhpOps.toStr(args[1]) : "");
            return true;
        });
        addMethod(language, methods, "header", (ctx, self, args) -> {
            requireAtLeast(RESPONSE + "::header", args, 2);
            requireAtMost(RESPONSE + "::header", args, 3);
            responseState(self).writer.setHeader(PhpOps.toStr(args[0]), PhpOps.toStr(args[1]));
            return true;
        });
        addMethod(language, methods, "cookie", (ctx, self, args) -> {
            requireAtMost(RESPONSE + "::cookie", args, 9);
            requireAtLeast(RESPONSE + "::cookie", args, 1);
            responseState(self).writer.addCookie(cookieLine(true, args));
            return true;
        });
        addMethod(language, methods, "rawCookie", (ctx, self, args) -> {
            requireAtMost(RESPONSE + "::rawCookie", args, 9);
            requireAtLeast(RESPONSE + "::rawCookie", args, 1);
            responseState(self).writer.addCookie(cookieLine(false, args));
            return true;
        });
        addMethod(language, methods, "write", (ctx, self, args) -> {
            requireExactly(RESPONSE + "::write", args, 1);
            return responseState(self).writer.write(PhpStrings.bytes(PhpOps.toStr(args[0])));
        });
        addMethod(language, methods, "end", (ctx, self, args) -> {
            requireAtMost(RESPONSE + "::end", args, 1);
            byte[] body = args.length > 0 && args[0] != PhpNull.INSTANCE
                    ? PhpStrings.bytes(PhpOps.toStr(args[0])) : EMPTY;
            responseState(self).writer.end(body);
            return PhpNull.INSTANCE;
        });
        addMethod(language, methods, "sendfile", (ctx, self, args) -> {
            requireAtLeast(RESPONSE + "::sendfile", args, 1);
            requireAtMost(RESPONSE + "::sendfile", args, 3);
            String path = PhpStrings.toJava(PhpOps.toStr(args[0]));
            long offset = args.length > 1 ? PhpOps.toLong(args[1]) : 0;
            long length = args.length > 2 ? PhpOps.toLong(args[2]) : 0;
            return responseState(self).writer.sendFile(Path.of(path), offset, length);
        });
        addMethod(language, methods, "isWritable", (ctx, self, args) -> {
            requireExactly(RESPONSE + "::isWritable", args, 0);
            return responseState(self).writer.isWritable();
        });
        addMethod(language, methods, "redirect", (ctx, self, args) -> {
            requireAtLeast(RESPONSE + "::redirect", args, 1);
            requireAtMost(RESPONSE + "::redirect", args, 2);
            String url = PhpOps.toStr(args[0]);
            int code = args.length > 1 ? (int) PhpOps.toLong(args[1]) : 302;
            responseState(self).writer.redirect(url, code);
            return true;
        });
        out.put(RESPONSE.toLowerCase(Locale.ROOT), new PhpClassTemplate(
                language.nextTemplateId(), RESPONSE, false, false, false, false, null,
                java.util.List.of(), java.util.List.of(), java.util.List.of(), methods));
    }

    // ---------------------------------------------------------------- request properties

    private static final String[] REQUEST_PROPERTIES = {
            "get", "post", "cookie", "files", "server", "header", "fd"
    };

    private static boolean isRequestProperty(String name) {
        for (String property : REQUEST_PROPERTIES) {
            if (property.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static Object requestProperty(PhpObject self, String name) {
        BlitzRequestState state = requestState(self);
        return switch (name) {
            case "get" -> state.get != null ? state.get : (state.get = state.request.getArray());
            case "post" -> state.post != null ? state.post : (state.post = state.request.postArray());
            case "cookie" -> state.cookie != null ? state.cookie
                    : (state.cookie = state.request.cookieArray());
            case "files" -> state.files != null ? state.files : (state.files = state.request.filesArray());
            case "server" -> state.server != null ? state.server
                    : (state.server = state.request.serverArray());
            case "header" -> state.header != null ? state.header
                    : (state.header = headerArray(state.request));
            case "fd" -> state.connectionId;
            default -> null;
        };
    }

    private static PhpArray headerArray(BlitzHttpRequest request) {
        PhpArray out = new PhpArray();
        for (Map.Entry<String, String> entry : request.headers().entrySet()) {
            out.put(entry.getKey(), entry.getValue());
        }
        return out;
    }

    private static String rawContent(BlitzRequestState state) {
        if (state.rawContent == null) {
            byte[] body = state.request.rawBody();
            state.rawContent = body == null || body.length == 0 ? "" : PhpStrings.of(body);
        }
        return state.rawContent;
    }

    // ---------------------------------------------------------------- cookies

    private static String cookieLine(boolean encode, Object[] args) {
        String name = PhpOps.toStr(args[0]);
        String value = args.length > 1 ? PhpOps.toStr(args[1]) : "";
        long expires = args.length > 2 ? PhpOps.toLong(args[2]) : 0;
        String path = args.length > 3 ? PhpOps.toStr(args[3]) : "/";
        String domain = args.length > 4 ? PhpOps.toStr(args[4]) : "";
        boolean secure = args.length > 5 && PhpOps.toBool(args[5]);
        boolean httpOnly = args.length > 6 && PhpOps.toBool(args[6]);
        String sameSite = args.length > 7 ? PhpOps.toStr(args[7]) : "";
        String priority = args.length > 8 ? PhpOps.toStr(args[8]) : "";
        if (name.isEmpty()) {
            throw new PhpError(PhpError.VALUE_ERROR,
                    "Blitz\\Http\\Response::cookie(): Argument #1 ($name) cannot be empty", null);
        }
        if (name.chars().anyMatch(c -> "=,; \t\r\n\u000b\f".indexOf(c) >= 0)) {
            throw new PhpError(PhpError.VALUE_ERROR,
                    "Blitz\\Http\\Response::cookie(): Argument #1 ($name) contains an invalid character", null);
        }
        if (!encode && value.chars().anyMatch(c -> ",; \t\r\n\u000b\f".indexOf(c) >= 0)) {
            throw new PhpError(PhpError.VALUE_ERROR,
                    "Blitz\\Http\\Response::rawCookie(): Argument #2 ($value) contains an invalid character", null);
        }
        checkCookieAttribute("path", path);
        checkCookieAttribute("domain", domain);
        StringBuilder line = new StringBuilder(name.length() + value.length() + 32);
        line.append(name).append('=');
        if (value.isEmpty()) {
            line.append("deleted; expires=Thu, 01 Jan 1970 00:00:01 GMT; Max-Age=0");
        } else {
            line.append(encode ? UrlFunctions.encode(value, false) : value);
            if (expires > 0) {
                long maxAge = Math.max(0, expires - System.currentTimeMillis() / 1000);
                line.append("; expires=").append(COOKIE_DATE.format(Instant.ofEpochSecond(expires)))
                        .append("; Max-Age=").append(maxAge);
            }
        }
        if (!path.isEmpty()) {
            line.append("; path=").append(path);
        }
        if (!domain.isEmpty()) {
            line.append("; domain=").append(domain);
        }
        if (secure) {
            line.append("; secure");
        }
        if (httpOnly) {
            line.append("; HttpOnly");
        }
        if (!sameSite.isEmpty()) {
            line.append("; SameSite=").append(sameSite);
        }
        if (!priority.isEmpty()) {
            line.append("; Priority=").append(priority);
        }
        return line.toString();
    }

    private static void checkCookieAttribute(String name, String value) {
        if (value.chars().anyMatch(c -> ",; \t\r\n\u000b\f".indexOf(c) >= 0)) {
            throw new PhpError(PhpError.VALUE_ERROR,
                    "Blitz\\Http\\Response::cookie(): \"" + name + "\" option contains an invalid character", null);
        }
    }

    // ---------------------------------------------------------------- native state

    static BlitzServerState serverState(PhpObject self) {
        if (self != null && self.nativeState() instanceof BlitzServerState state) {
            return state;
        }
        throw new PhpError("Blitz\\Http\\Server object is not initialized");
    }

    static BlitzRequestState requestState(PhpObject self) {
        if (self != null && self.nativeState() instanceof BlitzRequestState state) {
            return state;
        }
        throw new PhpError("Blitz\\Http\\Request object is not initialized");
    }

    static BlitzResponseState responseState(PhpObject self) {
        if (self != null && self.nativeState() instanceof BlitzResponseState state) {
            return state;
        }
        throw new PhpError("Blitz\\Http\\Response object is not initialized");
    }

    // ---------------------------------------------------------------- helpers

    private static void addMethod(PhpLanguage language, Map<String, PhpClassTemplate.MethodDecl> map,
                                  String name, dev.phpruntime.runtime.PhpExceptions.BuiltinMethod impl) {
        String lower = name.toLowerCase(Locale.ROOT);
        var root = new dev.phpruntime.runtime.PhpExceptions.BuiltinMethodRootNode(language, name, impl);
        map.put(lower, new PhpClassTemplate.MethodDecl(
                name, lower, language.methodId(name), Visibility.PUBLIC, false, false, false,
                root.getCallTarget()));
    }

    private static void requireExactly(String name, Object[] args, int count) {
        if (args.length != count) {
            throw argumentCount(name, "exactly " + count, count, args.length);
        }
    }

    private static void requireAtLeast(String name, Object[] args, int count) {
        if (args.length < count) {
            throw argumentCount(name, "at least " + count, count, args.length);
        }
    }

    private static void requireAtMost(String name, Object[] args, int count) {
        if (args.length > count) {
            throw argumentCount(name, "at most " + count, count, args.length);
        }
    }

    private static PhpError argumentCount(String name, String expected, int count, int actual) {
        return new PhpError(PhpError.ARGUMENT_COUNT,
                name + "() expects " + expected + " argument" + (count == 1 ? "" : "s")
                        + ", " + actual + " given", null);
    }

    private static PhpError typeError(String name, int position, String parameter, String expected, Object actual) {
        return new PhpError(PhpError.TYPE_ERROR,
                name + "(): Argument #" + position + " ($" + parameter + ") must be of type "
                        + expected + ", " + PhpOps.typeName(actual) + " given", null);
    }
}
