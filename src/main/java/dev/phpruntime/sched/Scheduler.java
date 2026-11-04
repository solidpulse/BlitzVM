package dev.phpruntime.sched;

import com.oracle.truffle.api.ThreadLocalAction;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.runtime.GuestScheduler;
import org.graalvm.polyglot.Context;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Preemptive time-slicing scheduler for PHP requests.
 *
 * <p>There are {@code cpus} execution slots (a fair semaphore). A request must hold a
 * slot to run guest code. Every {@code tick} a ticker thread looks at the requests that
 * are running; if one has used up its quantum while others are waiting, the ticker
 * submits a Truffle {@link ThreadLocalAction} to that request's thread. Truffle runs
 * the action at the guest's next safepoint (loop back-edges and function entry), where
 * the request hands its slot back and re-queues at the tail of the fair semaphore. That
 * is round-robin preemption without any cooperation from the PHP code, so
 * {@code while (true) {}} cannot starve a hello-world request.
 *
 * <p>Blocking builtins (sleep/usleep) also give up the slot while they wait, and a
 * per-request CPU budget enforces max_execution_time by cancelling the polyglot context.
 */
public final class Scheduler implements AutoCloseable {

    private final int cpus;
    private final Semaphore slots;
    private final long quantumNanos;
    private final long maxCpuNanos;
    private final ScheduledExecutorService ticker;
    private final Set<Task> running = ConcurrentHashMap.newKeySet();

    private final AtomicLong preemptions = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong timeouts = new AtomicLong();

    public Scheduler(int cpus, long quantumMillis, long maxExecutionMillis) {
        this.cpus = cpus;
        this.slots = new Semaphore(cpus, true);
        this.quantumNanos = TimeUnit.MILLISECONDS.toNanos(quantumMillis);
        this.maxCpuNanos = maxExecutionMillis <= 0 ? Long.MAX_VALUE : TimeUnit.MILLISECONDS.toNanos(maxExecutionMillis);
        this.ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "php-scheduler-tick");
            t.setDaemon(true);
            return t;
        });
        long tick = Math.max(1, quantumMillis / 2);
        ticker.scheduleAtFixedRate(this::tick, tick, tick, TimeUnit.MILLISECONDS);
    }

    public Task newTask(String name) {
        return new Task(name);
    }

    private void tick() {
        long now = System.nanoTime();
        for (Task t : running) {
            if (t.cpuNanos(now) > maxCpuNanos) {
                t.timeOut();
            } else if (now - t.sliceStart > quantumNanos && slots.hasQueuedThreads()) {
                t.requestPreemption();
            }
        }
    }

    public String stats() {
        return "cpus=" + cpus + " running=" + running.size() + " waiting=" + slots.getQueueLength()
                + " completed=" + completed.get() + " preemptions=" + preemptions.get() + " timeouts=" + timeouts.get();
    }

    public long maxExecutionMillis() {
        return maxCpuNanos == Long.MAX_VALUE ? 0 : TimeUnit.NANOSECONDS.toMillis(maxCpuNanos);
    }

    public long preemptions() {
        return preemptions.get();
    }

    @Override
    public void close() {
        ticker.shutdownNow();
    }

    /** One PHP request's scheduling state. */
    public final class Task implements GuestScheduler {

        private final String name;
        private volatile TruffleLanguage.Env env;
        private volatile Thread thread;
        private volatile Context context;
        private volatile long sliceStart;
        private long usedNanos;
        private final AtomicBoolean preemptPending = new AtomicBoolean();
        private volatile boolean timedOut;

        private Task(String name) {
            this.name = name;
        }

        /** Host side: wait for a CPU slot before entering guest code. */
        public void acquire() throws InterruptedException {
            slots.acquire();
            onSlotAcquired();
        }

        /** Host side: give the slot back after guest code finished. */
        public void release() {
            onSlotReleased();
            completed.incrementAndGet();
        }

        public void bindContext(Context ctx) {
            this.context = ctx;
        }

        public boolean timedOut() {
            return timedOut;
        }

        private void onSlotAcquired() {
            sliceStart = System.nanoTime();
            running.add(this);
        }

        private void onSlotReleased() {
            running.remove(this);
            usedNanos += System.nanoTime() - sliceStart;
            slots.release();
        }

        long cpuNanos(long now) {
            return usedNanos + (now - sliceStart);
        }

        @Override
        public void attach(TruffleLanguage.Env e, Thread t) {
            this.env = e;
            this.thread = t;
        }

        @Override
        public void detach() {
            this.env = null;
        }

        private void requestPreemption() {
            TruffleLanguage.Env e = env;
            Thread t = thread;
            if (e == null || t == null || !preemptPending.compareAndSet(false, true)) {
                return;
            }
            e.submitThreadLocal(new Thread[]{t}, new ThreadLocalAction(false, false) {
                @Override
                protected void perform(Access access) {
                    yieldSlot(access.getLocation());
                }
            });
        }

        /** Runs on the guest thread at a safepoint: go to the back of the run queue. */
        private void yieldSlot(Node location) {
            preemptPending.set(false);
            if (!slots.hasQueuedThreads()) {
                sliceStart = System.nanoTime(); // nobody waiting: keep running, fresh slice
                return;
            }
            preemptions.incrementAndGet();
            onSlotReleased();
            TruffleSafepoint.setBlockedThreadInterruptible(location, Semaphore::acquire, slots);
            onSlotAcquired();
        }

        @Override
        public void blocking(Node location, InterruptibleRunnable op) {
            onSlotReleased();
            try {
                TruffleSafepoint.setBlockedThreadInterruptible(location, InterruptibleRunnable::run, op);
            } finally {
                TruffleSafepoint.setBlockedThreadInterruptible(location, Semaphore::acquire, slots);
                onSlotAcquired();
            }
        }

        private void timeOut() {
            if (timedOut) {
                return;
            }
            timedOut = true;
            timeouts.incrementAndGet();
            Context c = context;
            if (c != null) {
                // Cancels guest execution at its next safepoint; the host sees a cancelled PolyglotException.
                Thread.ofPlatform().daemon().start(() -> c.close(true));
            }
        }

        @Override
        public String toString() {
            return "Task[" + name + "]";
        }
    }
}
