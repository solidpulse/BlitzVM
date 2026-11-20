package dev.phpruntime.builtins;

import java.util.List;

/**
 * The request-local state a boot snapshot carries from {@link ContextState}: the settings that
 * affect how later builtin calls behave, and the resource-id counter.
 */
public record ContextStateSnapshot(String timezoneName, String mbEncoding, Object mbSubstitute,
                                   List<String> mbDetectOrder, long nextResourceId,
                                   boolean defaultStreamContext, long umask, long ignoreUserAbort) {

    public ContextStateSnapshot {
        mbDetectOrder = List.copyOf(mbDetectOrder);
    }
}
