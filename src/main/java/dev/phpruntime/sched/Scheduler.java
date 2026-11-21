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
    private final AtomicLong heldNanos = new AtomicLong();
    private final AtomicLong waitNanos = new AtomicLong();
    private final AtomicLong grants = new AtomicLong();

    public Scheduler(int cpus, long quantumMillis, long maxExecutionMillis) {
        this.cpus = cpus;
        // Non-fair: a released slot goes to whichever ready request takes it first. A fair
        // semaphore hands it to the longest-parked thread, and the slot sits idle until the OS
        // has woken that thread, which on short requests cost more than the request itself
        // (hello: 35k req/s fair, 53k non-fair). Preemption keeps its round-robin guarantee in
        // yieldSlot. -Dblitz.sched.fair=true restores the strict FIFO hand-off.
        this.slots = new Semaphore(cpus, Boolean.getBoolean("blitz.sched.fair"));
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
                + " completed=" + completed.get() + " preemptions=" + preemptions.get() + " timeouts=" + timeouts.get()
                + " grants=" + grants.get() + " heldMs=" + TimeUnit.NANOSECONDS.toMillis(heldNanos.get())
                + " slotWaitMs=" + TimeUnit.NANOSECONDS.toMillis(waitNanos.get());
    }

    public long maxExecutionMillis() {
        return maxCpuNanos == Long.MAX_VALUE ? 0 : TimeUnit.NANOSECONDS.toMillis(maxCpuNanos);
    }

    /** Execution slots; the host sizes its polyglot context pool from this. */
    public int cpus() {
        return cpus;
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
        /** Set by release(): the ticker must not start a close for a request that has finished. */
        private boolean finished;

        private Task(String name) {
            this.name = name;
        }

        /** Host side: wait for a CPU slot before entering guest code. */
        public void acquire() throws InterruptedException {
            long t0 = System.nanoTime();
            slots.acquire();
            waitNanos.addAndGet(System.nanoTime() - t0);
            onSlotAcquired();
        }

        /** Host side: give the slot back after guest code finished. */
        public void release() {
            // Once this returns, the ticker can no longer decide to close this task's context,
            // so the host may safely return the polyglot context to its pool.
            synchronized (this) {
                finished = true;
            }
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
            grants.incrementAndGet();
            sliceStart = System.nanoTime();
            running.add(this);
        }

        private void onSlotReleased() {
            running.remove(this);
            long held = System.nanoTime() - sliceStart;
            usedNanos += held;
            heldNanos.addAndGet(held);
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
            synchronized (this) {
                if (finished) {
                    // The request finished before this action arrived; the next guest entry on
                    // this thread belongs to another task and must keep its own slot.
                    return;
                }
            }
            if (!slots.hasQueuedThreads()) {
                sliceStart = System.nanoTime(); // nobody waiting: keep running, fresh slice
                return;
            }
            preemptions.incrementAndGet();
            long grantsBefore = grants.get();
            onSlotReleased();
            // With non-fair slots this thread would usually win its own slot straight back; wait
            // until a waiting request has been granted one (or nobody waits any more) so a
            // CPU-bound script really goes to the back of the line.
            TruffleSafepoint.setBlockedThreadInterruptible(location, (Long g) -> awaitHandoff(g), grantsBefore);
            TruffleSafepoint.setBlockedThreadInterruptible(location, Semaphore::acquire, slots);
            onSlotAcquired();
        }

        private void awaitHandoff(long grantsBefore) throws InterruptedException {
            Scheduler s = Scheduler.this;
            while (s.grants.get() == grantsBefore && s.slots.hasQueuedThreads()) {
                java.util.concurrent.locks.LockSupport.parkNanos(20_000);
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
            }
        }

        @Override
        public void blocking(Node location, InterruptibleRunnable op) {
            onSlotReleased();
            try {
                TruffleSafepoint.setBlockedThreadInterruptible(location, InterruptibleRunnable::run, op);
            } finally {
                long t0 = System.nanoTime();
                TruffleSafepoint.setBlockedThreadInterruptible(location, Semaphore::acquire, slots);
                waitNanos.addAndGet(System.nanoTime() - t0);
                onSlotAcquired();
            }
        }

        private void timeOut() {
            synchronized (this) {
                if (timedOut || finished) {
                    return;
                }
                timedOut = true;
            }
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
