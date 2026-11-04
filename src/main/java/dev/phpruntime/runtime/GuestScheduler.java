package dev.phpruntime.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.Node;

/**
 * What the guest needs from a scheduler. The guest never decides when to yield:
 * the scheduler submits a Truffle thread-local action which runs at the next guest
 * safepoint (loop back-edge or call), so preemption works even inside {@code while(true){}}.
 */
public interface GuestScheduler {

    /** Called by the main root node once guest code starts running on a thread. */
    void attach(TruffleLanguage.Env env, Thread thread);

    /** Called when guest execution on this thread ends. */
    void detach();

    /**
     * Run a blocking host operation (sleep, I/O) while giving up the CPU slot, the
     * way a goroutine parks on a syscall.
     */
    void blocking(Node location, InterruptibleRunnable op);

    @FunctionalInterface
    interface InterruptibleRunnable {
        void run() throws InterruptedException;
    }

    GuestScheduler NONE = new GuestScheduler() {
        @Override
        public void attach(TruffleLanguage.Env env, Thread thread) {
        }

        @Override
        public void detach() {
        }

        @Override
        public void blocking(Node location, InterruptibleRunnable op) {
            com.oracle.truffle.api.TruffleSafepoint.setBlockedThreadInterruptible(location, InterruptibleRunnable::run, op);
        }
    };
}
