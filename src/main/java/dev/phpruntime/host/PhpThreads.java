package dev.phpruntime.host;

/**
 * Threads that run PHP code. The JVM's default 1 MB stack is far smaller than what php
 * gets: php keeps userland frames and PCRE backtracking on the heap, while BlitzVM uses
 * the Java stack for guest calls and java.util.regex recurses once per iteration of a
 * repeated group, so {@code (.|\n)*?} over a 1.5 KB subject overflowed a 1 MB stack.
 * The stack is reserved address space; only the pages a thread touches are committed.
 */
final class PhpThreads {

    static final long STACK_BYTES = 64L << 20;

    private PhpThreads() {
    }

    static Thread newThread(Runnable r, String name, boolean daemon) {
        Thread t = new Thread(null, r, name, STACK_BYTES);
        t.setDaemon(daemon);
        return t;
    }
}
