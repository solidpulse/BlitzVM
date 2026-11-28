package dev.phpruntime.host;

/** Constant server facts a request reports in {@code $request->server}. */
public record BlitzServerInfo(String host, int port, String scriptFilename, String documentRoot) {
}
