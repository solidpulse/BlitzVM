package dev.phpruntime.builtins;

import com.oracle.truffle.api.TruffleFile;
import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.HostFiles;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpStrings;

import java.io.IOException;

/** mime_content_type: a magic-byte table for the types php's libmagic commonly reports. */
final class MimeFunctions {

    private MimeFunctions() {
    }

    static Object detect(PhpContext ctx, Object[] a) {
        String path = PhpOps.toStr(a[0]);
        try {
            String javaPath = PhpStrings.toJava(path);
            TruffleFile file = ctx.env().getPublicTruffleFile(javaPath);
            Boolean hostExists = HostFiles.exists(ctx, javaPath);
            Boolean hostDirectory = HostFiles.isDirectory(ctx, javaPath);
            boolean exists = hostExists != null ? hostExists : file.exists();
            boolean directory = hostDirectory != null ? hostDirectory : file.isDirectory();
            if (!exists || directory) {
                throw new IOException();
            }
            return detectBytes(file.readAllBytes());
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            ctx.warning("mime_content_type(" + path + "): Failed to open stream: No such file or directory", 0);
            return false;
        }
    }

    static String detectBytes(byte[] d) {
        if (d.length == 0) {
            return "application/x-empty";
        }
        String head = PhpStrings.of(d, 0, Math.min(d.length, 4096));
        if (starts(d, 0x1f, 0x8b)) {
            return "application/gzip";
        }
        if (starts(d, 0xff, 0xd8, 0xff)) {
            return "image/jpeg";
        }
        if (starts(d, 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a)) {
            return d.length >= 16 && ascii(d, 12, "IHDR") ? "image/png" : "application/octet-stream";
        }
        if (ascii(d, 0, "GIF87a") || ascii(d, 0, "GIF89a")) {
            return "image/gif";
        }
        if (d.length >= 12 && ascii(d, 0, "RIFF") && ascii(d, 8, "WEBP")) {
            return "image/webp";
        }
        if (d.length >= 12 && ascii(d, 0, "RIFF") && ascii(d, 8, "WAVE")) {
            return "audio/x-wav";
        }
        if (starts(d, 'B', 'M') && d.length >= 14) {
            return "image/bmp";
        }
        if (ascii(d, 0, "%PDF-")) {
            return "application/pdf";
        }
        if (starts(d, 'P', 'K', 3, 4)) {
            return d.length >= 30 ? "application/zip" : "application/octet-stream";
        }
        if (d.length >= 262 && ascii(d, 257, "ustar")) {
            return "application/x-tar";
        }
        if (ascii(d, 0, "OggS") && d.length >= 27) {
            return "audio/ogg";
        }
        if (ascii(d, 0, "ID3") || d.length > 1 && (d[0] & 0xff) == 0xff && (d[1] & 0xe0) == 0xe0) {
            return "audio/mpeg";
        }
        if (d.length >= 12 && ascii(d, 4, "ftyp")) {
            return "video/mp4";
        }
        String trimmed = head.strip();
        if (trimmed.startsWith("<?php") || trimmed.startsWith("<?=")) {
            return "text/x-php";
        }
        if (trimmed.startsWith("#!")) {
            String first = trimmed.split("\n", 2)[0].toLowerCase();
            if (first.contains("python")) {
                return "text/x-python";
            }
            if (first.contains("perl")) {
                return "text/x-perl";
            }
            return "text/x-shellscript";
        }
        if (trimmed.startsWith("<?xml")) {
            return "text/xml";
        }
        String lower = PhpStrings.lower(trimmed);
        if (lower.contains("<svg") && (lower.contains("xmlns") || lower.startsWith("<svg"))) {
            return "image/svg+xml";
        }
        if (lower.startsWith("<!doctype html") || lower.startsWith("<html")) {
            return "text/html";
        }
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            String compact = trimmed.replaceAll("\\s+", "");
            if (compact.matches("\\{.*:.*\\}") || compact.matches("\\[.*\\]")) {
                return "application/json";
            }
        }
        if (isText(d)) {
            return "text/plain";
        }
        return "application/octet-stream";
    }

    private static boolean starts(byte[] d, int... values) {
        if (d.length < values.length) {
            return false;
        }
        for (int i = 0; i < values.length; i++) {
            if ((d[i] & 0xff) != (values[i] & 0xff)) {
                return false;
            }
        }
        return true;
    }

    private static boolean ascii(byte[] d, int offset, String text) {
        if (offset < 0 || d.length < offset + text.length()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (d[offset + i] != text.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** libmagic's text/plain result: printable ASCII, whitespace, or valid UTF-8 without NULs. */
    private static boolean isText(byte[] d) {
        for (byte b : d) {
            int c = b & 0xff;
            if (c == 0) {
                return false;
            }
            if (c < 32 && c != '\n' && c != '\r' && c != '\t' && c != '\f' && c != 0x0b) {
                return false;
            }
        }
        String s = PhpStrings.of(d);
        if (PhpStrings.isAscii(s)) {
            return true;
        }
        return PhpStrings.isUtf8(s);
    }
}
