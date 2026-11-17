package dev.phpruntime.builtins;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.nodes.PhpRootNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpClassTemplate;
import dev.phpruntime.runtime.PhpConstants;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpStrings;
import dev.phpruntime.runtime.Visibility;

import java.net.InetAddress;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Error log, ini and constant listings, the HTML translation table, inet and DNS helpers,
 * class introspection, Unicode normalization, timezone listing, array_rand and session stubs.
 */
final class MiscFunctions {

    private MiscFunctions() {
    }

    static void register() {
        errors();
        network();
        introspection();
        Builtins.def("get_html_translation_table", 0, 3, (ctx, a) -> translationTable(
                a.length > 0 ? PhpOps.toLong(a[0]) : 0, a.length > 1 ? PhpOps.toLong(a[1]) : HtmlEntities.DEFAULT_FLAGS,
                a.length > 2 ? PhpOps.toStr(a[2]) : "UTF-8"));
        Builtins.def("session_status", 0, 0, (ctx, a) -> 1L); // PHP_SESSION_NONE: sessions are not supported
        Builtins.def("opcache_get_status", 0, 1, (ctx, a) -> false);
        Builtins.def("getcwd", 0, 0, (ctx, a) -> {
            try {
                return PhpStrings.fromJava(ctx.env().getCurrentWorkingDirectory().getPath());
            } catch (SecurityException e) {
                return false;
            }
        });
        Builtins.def("array_rand", 1, 2, (ctx, a) -> arrayRand(a));
        Builtins.def("normalizer_normalize", 1, 2, (ctx, a) -> {
            java.text.Normalizer.Form form = form(a.length > 1 ? PhpOps.toLong(a[1]) : 16);
            return form == null ? false : PhpStrings.fromJava(java.text.Normalizer.normalize(PhpStrings.toJava(PhpOps.toStr(a[0])), form));
        });
        Builtins.def("normalizer_is_normalized", 1, 2, (ctx, a) -> {
            java.text.Normalizer.Form form = form(a.length > 1 ? PhpOps.toLong(a[1]) : 16);
            return form != null && java.text.Normalizer.isNormalized(PhpStrings.toJava(PhpOps.toStr(a[0])), form);
        });
    }

    // ------------------------------------------------------------------ errors and ini

    private static void errors() {
        Builtins.def("error_log", 1, 4, (ctx, a) -> {
            String message = PhpOps.toStr(a[0]);
            long type = a.length > 1 ? PhpOps.toLong(a[1]) : 0;
            if (type == 3) {
                String dest = a.length > 2 ? PhpOps.toStr(a[2]) : "";
                try {
                    ctx.env().getPublicTruffleFile(PhpStrings.toJava(dest)).newByteChannel(Set.of(java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.APPEND))
                            .write(java.nio.ByteBuffer.wrap(PhpStrings.bytes(message)));
                    return true;
                } catch (java.io.IOException | SecurityException | IllegalArgumentException e) {
                    ctx.warning("error_log(" + dest + "): Failed to open stream: No such file or directory", 0);
                    return false;
                }
            }
            if (type == 1) {
                return false; // mail is not supported
            }
            try {
                java.io.OutputStream err = ctx.env().err();
                err.write(PhpStrings.bytes(message + "\n"));
                err.flush();
            } catch (java.io.IOException e) {
                return false;
            }
            return true;
        });
        // Errors are not recorded yet, so there is never a last error.
        Builtins.def("error_get_last", 0, 0, (ctx, a) -> PhpNull.INSTANCE);
        Builtins.def("error_clear_last", 0, 0, (ctx, a) -> PhpNull.INSTANCE);
        Builtins.def("ini_get_all", 0, 2, (ctx, a) -> {
            boolean details = a.length < 2 || PhpOps.toBool(a[1]);
            PhpArray out = new PhpArray();
            List<String> names = new ArrayList<>(EnvFunctions.iniValues().keySet());
            names.sort(String::compareTo);
            String ext = a.length > 0 && a[0] != PhpNull.INSTANCE ? PhpOps.toStr(a[0]) : null;
            for (String name : names) {
                if (ext != null && !name.startsWith(ext + ".")) {
                    continue;
                }
                String v = EnvFunctions.iniValues().get(name);
                if (details) {
                    PhpArray d = new PhpArray();
                    d.put("global_value", v);
                    d.put("local_value", v);
                    d.put("access", 7L);
                    out.put(name, d);
                } else {
                    out.put(name, v);
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ network

    private static void network() {
        Builtins.def("ip2long", 1, 1, (ctx, a) -> {
            byte[] b = packAddress(PhpOps.toStr(a[0]));
            if (b == null || b.length != 4) {
                return false;
            }
            return ((long) (b[0] & 0xff) << 24) | ((long) (b[1] & 0xff) << 16)
                    | ((long) (b[2] & 0xff) << 8) | (b[3] & 0xff);
        });
        Builtins.def("long2ip", 1, 1, (ctx, a) -> {
            long v = PhpOps.toLong(a[0]) & 0xffffffffL;
            return ((v >> 24) & 0xff) + "." + ((v >> 16) & 0xff) + "." + ((v >> 8) & 0xff) + "." + (v & 0xff);
        });
        Builtins.def("gethostbynamel", 1, 1, (ctx, a) -> {
            String host = PhpOps.toStr(a[0]);
            java.util.LinkedHashSet<String> addresses = new java.util.LinkedHashSet<>();
            // DNS is network I/O: give up the CPU slot while waiting.
            ctx.request().scheduler.blocking(null, () -> {
                try {
                    for (InetAddress addr : InetAddress.getAllByName(PhpStrings.toJava(host))) {
                        if (addr instanceof java.net.Inet4Address) {
                            addresses.add(addr.getHostAddress());
                        }
                    }
                } catch (java.net.UnknownHostException | SecurityException e) {
                    // php returns false when no IPv4 address is found
                }
            });
            if (addresses.isEmpty()) {
                return false;
            }
            PhpArray out = new PhpArray();
            for (String addr : addresses) {
                out.append(addr);
            }
            return out;
        });
        Builtins.def("inet_pton", 1, 1, (ctx, a) -> {
            byte[] packed = packAddress(PhpOps.toStr(a[0]));
            return packed == null ? false : PhpStrings.of(packed);
        });
        Builtins.def("inet_ntop", 1, 1, (ctx, a) -> {
            byte[] b = PhpStrings.bytes(PhpOps.toStr(a[0]));
            if (b.length != 4 && b.length != 16) {
                return false;
            }
            try {
                InetAddress addr = InetAddress.getByAddress(b);
                return b.length == 4 ? addr.getHostAddress() : compressIpv6(b);
            } catch (java.net.UnknownHostException e) {
                return false;
            }
        });
        Builtins.def("gethostbyname", 1, 1, (ctx, a) -> {
            String host = PhpOps.toStr(a[0]);
            String[] result = {host};
            // DNS is network I/O: give up the CPU slot while waiting.
            ctx.request().scheduler.blocking(null, () -> {
                try {
                    for (InetAddress addr : InetAddress.getAllByName(PhpStrings.toJava(host))) {
                        if (addr instanceof java.net.Inet4Address) {
                            result[0] = addr.getHostAddress();
                            return;
                        }
                    }
                } catch (java.net.UnknownHostException | SecurityException e) {
                    // PHP returns the name unchanged on failure
                }
            });
            return result[0];
        });
        Builtins.def("gethostbyaddr", 1, 1, (ctx, a) -> {
            String ip = PhpOps.toStr(a[0]);
            byte[] packed = packAddress(ip);
            if (packed == null) {
                ctx.warning("gethostbyaddr(): Address is not a valid IPv4 or IPv6 address", 0);
                return false;
            }
            String[] result = {ip};
            ctx.request().scheduler.blocking(null, () -> {
                try {
                    result[0] = PhpStrings.fromJava(InetAddress.getByAddress(packed).getCanonicalHostName());
                } catch (java.net.UnknownHostException | SecurityException e) {
                    // keep the address
                }
            });
            return result[0];
        });
    }

    /** inet_pton: 4 bytes for dotted IPv4, 16 for IPv6, null if neither. Never resolves names. */
    static byte[] packAddress(String s) {
        if (s.indexOf(':') < 0) {
            String[] parts = s.split("\\.", -1);
            if (parts.length != 4) {
                return null;
            }
            byte[] out = new byte[4];
            for (int i = 0; i < 4; i++) {
                String p = parts[i];
                if (p.isEmpty() || p.length() > 3 || !p.chars().allMatch(Character::isDigit)
                        || (p.length() > 1 && p.charAt(0) == '0')) {
                    return null;
                }
                int v = Integer.parseInt(p);
                if (v > 255) {
                    return null;
                }
                out[i] = (byte) v;
            }
            return out;
        }
        if (!s.matches("[0-9A-Fa-f:.]+") || s.contains(":::")) {
            return null;
        }
        try {
            InetAddress addr = InetAddress.getByName("[" + s + "]");
            byte[] b = addr.getAddress();
            if (b.length == 4) {
                // an IPv4-mapped literal; keep the 16-byte form PHP returns
                byte[] mapped = new byte[16];
                mapped[10] = (byte) 0xff;
                mapped[11] = (byte) 0xff;
                System.arraycopy(b, 0, mapped, 12, 4);
                return mapped;
            }
            return b;
        } catch (java.net.UnknownHostException e) {
            return null;
        }
    }

    /** RFC 5952 text for an IPv6 address, as inet_ntop prints it. */
    static String compressIpv6(byte[] b) {
        int[] w = new int[8];
        for (int i = 0; i < 8; i++) {
            w[i] = ((b[i * 2] & 0xff) << 8) | (b[i * 2 + 1] & 0xff);
        }
        boolean mapped = w[0] == 0 && w[1] == 0 && w[2] == 0 && w[3] == 0 && w[4] == 0 && w[5] == 0xffff;
        if (mapped) {
            return "::ffff:" + (b[12] & 0xff) + "." + (b[13] & 0xff) + "." + (b[14] & 0xff) + "." + (b[15] & 0xff);
        }
        int bestStart = -1;
        int bestLen = 0;
        for (int i = 0; i < 8; ) {
            if (w[i] != 0) {
                i++;
                continue;
            }
            int j = i;
            while (j < 8 && w[j] == 0) {
                j++;
            }
            if (j - i > bestLen && j - i >= 2) {
                bestStart = i;
                bestLen = j - i;
            }
            i = j;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == bestStart) {
                sb.append("::");
                i += bestLen - 1;
                continue;
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ':') {
                sb.append(':');
            }
            sb.append(Integer.toHexString(w[i]));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ introspection

    private static void introspection() {
        Builtins.def("get_defined_constants", 0, 1, (ctx, a) -> {
            boolean categorize = a.length > 0 && PhpOps.toBool(a[0]);
            PhpArray core = new PhpArray();
            List<String> names = new ArrayList<>(PhpConstants.all().keySet());
            names.sort(String::compareTo);
            for (String n : names) {
                core.put(n, PhpConstants.all().get(n));
            }
            PhpArray user = new PhpArray();
            for (Map.Entry<String, Object> e : ctx.userConstants().entrySet()) {
                user.put(e.getKey(), e.getValue());
            }
            if (categorize) {
                PhpArray out = new PhpArray();
                out.put("Core", core);
                if (user.size() > 0) {
                    out.put("user", user);
                }
                return out;
            }
            for (var e : user.entries()) {
                core.put(e.getKey(), e.getValue());
            }
            return core;
        });
        Builtins.def("get_class_methods", 1, 1, (ctx, a) -> {
            PhpClass cls = a[0] instanceof PhpObject o ? o.phpClass() : ctx.lookupClass(PhpOps.toStr(a[0]));
            if (cls == null) {
                throw new PhpError(PhpError.TYPE_ERROR, "get_class_methods(): Argument #1 ($object_or_class) must be an object or a valid class name, "
                        + PhpOps.typeName(a[0]) + " given", null);
            }
            String callerName = callingClass();
            PhpClass caller = callerName == null ? null : ctx.lookupClass(callerName);
            PhpArray out = new PhpArray();
            Set<String> seen = new HashSet<>();
            for (PhpClass c = cls; c != null; c = c.parent()) {
                for (PhpClassTemplate.MethodDecl m : c.template().methods().values()) {
                    if (!seen.add(m.lowerName())) {
                        continue;
                    }
                    PhpClass.MethodEntry entry = cls.lookupMethod(m.lowerName());
                    if (entry != null && visible(entry, caller)) {
                        out.append(entry.name());
                    }
                }
            }
            return out;
        });
        Builtins.def("class_alias", 2, 3, (ctx, a) -> {
            String original = PhpOps.toStr(a[0]);
            String alias = PhpOps.toStr(a[1]);
            PhpClass cls = ctx.lookupClass(original);
            if (cls == null) {
                ctx.warning("Class \"" + original + "\" not found", 0);
                return false;
            }
            if (!ctx.aliasClass(alias, cls)) {
                ctx.warning("Cannot declare class " + alias + ", because the name is already in use", 0);
                return false;
            }
            return true;
        });
    }

    private static boolean visible(PhpClass.MethodEntry m, PhpClass caller) {
        if (m.visibility() == Visibility.PUBLIC) {
            return true;
        }
        if (caller == null) {
            return false;
        }
        if (m.visibility() == Visibility.PRIVATE) {
            return caller == m.declaringClass();
        }
        return caller.isInstanceOf(m.declaringClass()) || m.declaringClass().isInstanceOf(caller);
    }

    /** The class whose method called the builtin, or null at top level. */
    private static String callingClass() {
        String[] found = new String[1];
        Truffle.getRuntime().iterateFrames(frameInstance -> {
            if (frameInstance.getCallTarget() instanceof RootCallTarget rct
                    && rct.getRootNode() instanceof PhpRootNode root) {
                if (dev.phpruntime.nodes.PhpFunctionRootNode.of(root) instanceof dev.phpruntime.nodes.PhpFunctionRootNode || root instanceof dev.phpruntime.nodes.generator.GeneratorResumeRootNode) {
                    PhpFunctionRootNode fn = dev.phpruntime.nodes.PhpFunctionRootNode.of(root) instanceof dev.phpruntime.nodes.PhpFunctionRootNode f ? f
                            : ((dev.phpruntime.nodes.generator.GeneratorResumeRootNode) root).signature;
                    com.oracle.truffle.api.frame.Frame runtimeFrame =
                            frameInstance.getFrame(com.oracle.truffle.api.frame.FrameInstance.FrameAccess.READ_ONLY);
                    if (root instanceof dev.phpruntime.nodes.generator.GeneratorResumeRootNode) {
                        runtimeFrame = ((dev.phpruntime.runtime.PhpGenerator) runtimeFrame.getArguments()[0]).frame;
                    }
                    if (fn.traitSelfSlot() >= 0) {
                        Object ts = runtimeFrame != null ? runtimeFrame.getObject(fn.traitSelfSlot()) : null;
                        found[0] = ts instanceof dev.phpruntime.runtime.PhpClass c ? c.name() : fn.declaringClass();
                    } else {
                        found[0] = fn.declaringClass();
                    }
                }
                return Boolean.TRUE;
            }
            return null;
        });
        return found[0];
    }

    // ------------------------------------------------------------------ misc helpers

    /** HTML_SPECIALCHARS (0) or HTML_ENTITIES (1) for the HTML 4.01 doctype. */
    private static PhpArray translationTable(long table, long flags, String encoding) {
        List<Map.Entry<Integer, String>> rows = new ArrayList<>();
        boolean doubleQuote = (flags & 2) != 0;
        boolean singleQuote = (flags & 1) != 0;
        if (table == 1) {
            for (Map.Entry<String, Integer> e : HtmlEntities.namedTable()) {
                if (e.getValue() == '"' && !doubleQuote) {
                    continue;
                }
                rows.add(Map.entry(e.getValue(), "&" + e.getKey() + ";"));
            }
        } else {
            if (doubleQuote) {
                rows.add(Map.entry((int) '"', "&quot;"));
            }
            rows.add(Map.entry((int) '&', "&amp;"));
            rows.add(Map.entry((int) '<', "&lt;"));
            rows.add(Map.entry((int) '>', "&gt;"));
        }
        if (singleQuote) {
            rows.add(Map.entry((int) '\'', (flags & 48) == HtmlEntities.ENT_HTML401 ? "&#039;" : "&apos;"));
        }
        rows.sort(Map.Entry.comparingByKey());
        PhpArray out = new PhpArray();
        java.nio.charset.Charset charset = HtmlEntities.charset(encoding);
        for (Map.Entry<Integer, String> r : rows) {
            String text = new String(Character.toChars(r.getKey()));
            if (PhpStrings.canEncode(text, charset)) {
                out.put(PhpStrings.encode(text, charset, "?"), r.getValue());
            }
        }
        return out;
    }

    private static final Set<String> TZ_REGIONS = Set.of("Africa", "America", "Antarctica", "Arctic", "Asia",
            "Atlantic", "Australia", "Europe", "Indian", "Pacific");

    /** Canonical region/city zones plus UTC, sorted, as timezone_identifiers_list() lists them. */
    private static PhpArray timezones() {
        List<String> ids = new ArrayList<>();
        for (String id : ZoneId.getAvailableZoneIds()) {
            int slash = id.indexOf('/');
            if (slash > 0 && TZ_REGIONS.contains(id.substring(0, slash)) && !LEGACY_ZONES.contains(id)) {
                ids.add(id);
            }
        }
        ids.add("UTC");
        ids.sort(String::compareTo);
        return PhpArray.ofList(ids);
    }

    /** Backward-compatibility links in tzdata that PHP's list leaves out. */
    private static final Set<String> LEGACY_ZONES = Set.of(
            "America/Buenos_Aires", "America/Catamarca", "America/Cordoba", "America/Jujuy", "America/Mendoza",
            "America/Indianapolis", "America/Knox_IN", "America/Louisville", "America/Fort_Wayne",
            "America/Porto_Acre", "America/Rosario", "America/Shiprock", "America/Virgin", "America/Ensenada",
            "America/Atka", "America/Coral_Harbour", "America/Montreal", "America/Santa_Isabel",
            "America/Godthab", "America/Thunder_Bay", "America/Nipigon", "America/Rainy_River",
            "America/Yellowknife", "America/Pangnirtung",
            "Antarctica/South_Pole", "Asia/Ashkhabad", "Asia/Calcutta", "Asia/Chongqing", "Asia/Chungking",
            "Asia/Dacca", "Asia/Harbin", "Asia/Istanbul", "Asia/Kashgar", "Asia/Katmandu", "Asia/Macao",
            "Asia/Rangoon", "Asia/Saigon", "Asia/Tel_Aviv", "Asia/Thimbu", "Asia/Ujung_Pandang",
            "Asia/Ulan_Bator", "Atlantic/Faeroe", "Atlantic/Jan_Mayen", "Australia/ACT", "Australia/Canberra",
            "Australia/LHI", "Australia/NSW", "Australia/North", "Australia/Queensland", "Australia/South",
            "Australia/Tasmania", "Australia/Victoria", "Australia/West", "Australia/Yancowinna",
            "Australia/Currie", "Europe/Belfast", "Europe/Nicosia", "Europe/Tiraspol", "Europe/Kiev",
            "Europe/Uzhgorod", "Europe/Zaporozhye", "Pacific/Johnston", "Pacific/Ponape", "Pacific/Samoa",
            "Pacific/Truk", "Pacific/Yap", "Pacific/Enderbury", "Africa/Asmera", "Africa/Timbuktu");

    private static Object arrayRand(Object[] a) {
        if (!(a[0] instanceof PhpArray arr) || arr.size() == 0) {
            throw new PhpError("ValueError", "array_rand(): Argument #1 ($array) cannot be empty", null);
        }
        long num = a.length > 1 ? PhpOps.toLong(a[1]) : 1;
        if (num < 1 || num > arr.size()) {
            throw new PhpError("ValueError", "array_rand(): Argument #2 ($num) must be between 1 and the number of elements in argument #1 ($array)", null);
        }
        List<Object> keys = arr.keys();
        java.util.Random rnd = java.util.concurrent.ThreadLocalRandom.current();
        if (num == 1) {
            return keys.get(rnd.nextInt(keys.size()));
        }
        // Pick num keys, keeping the array's order (selection sampling).
        PhpArray out = new PhpArray();
        long needed = num;
        for (int i = 0; i < keys.size() && needed > 0; i++) {
            if (rnd.nextInt(keys.size() - i) < needed) {
                out.append(keys.get(i));
                needed--;
            }
        }
        return out;
    }

    /** Normalizer::FORM_* values (FORM_D 4, FORM_KD 8, FORM_C 16, FORM_KC 32). */
    private static java.text.Normalizer.Form form(long f) {
        return switch ((int) f) {
            case 4 -> java.text.Normalizer.Form.NFD;
            case 8 -> java.text.Normalizer.Form.NFKD;
            case 16 -> java.text.Normalizer.Form.NFC;
            case 32 -> java.text.Normalizer.Form.NFKC;
            default -> null;
        };
    }
}
