package dev.phpruntime.host;

import dev.phpruntime.runtime.PhpCallable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.AcceptPendingException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The listening sockets and the fixed set of worker selector loops.
 *
 * <p>Every worker owns a {@link Selector}. Where SO_REUSEPORT is available each worker also
 * binds its own listening socket, so the kernel spreads new connections across the workers
 * instead of letting whichever worker selects first take a burst. Where it is not available,
 * every worker registers the one shared listening channel and only idle workers race for new
 * connections. A worker that is running PHP is not selecting, so a blocking handler stalls its
 * own connections, not the accept path for the whole server. There is no acceptor thread and
 * no queue of requests: accept, parse, run the handler, write, all on one worker thread.
 */
final class BlitzServerCore {

    private final BlitzServerInfo info;
    private final int workerNum;
    private final int maxBodyBytes;
    private final long keepAliveTimeoutMillis;
    private final int bufferOutputBytes;
    private final String host;
    private final int backlog;
    private final ServerSocketChannel listener;
    private final boolean reusePort;
    private final ServerSocketChannel[] listeners;
    private final int port;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger nextIndex = new AtomicInteger(1);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicLong connectionIds = new AtomicLong();
    private final CountDownLatch ready;
    private final CountDownLatch gate = new CountDownLatch(1);
    private final CountDownLatch finished;
    private final CountDownLatch allFinished;
    private final Selector[] selectors;
    private final BlitzServerWorker[] workers;
    private final boolean stats = Boolean.getBoolean("blitz.server.stats");
    private Thread shutdownHook;

    BlitzServerCore(BlitzServerInfo info, String host, int requestedPort,
                    int workerNum, int backlog, int maxBodyBytes,
                    int keepAliveTimeoutSeconds, int bufferOutputBytes) {
        this.info = info;
        this.workerNum = workerNum;
        this.maxBodyBytes = maxBodyBytes;
        this.keepAliveTimeoutMillis = Math.max(0, keepAliveTimeoutSeconds) * 1000L;
        this.bufferOutputBytes = bufferOutputBytes;
        this.host = host;
        this.backlog = backlog;
        this.reusePort = reusePortSupported();
        this.ready = new CountDownLatch(Math.max(0, workerNum - 1));
        this.finished = new CountDownLatch(Math.max(0, workerNum - 1));
        this.allFinished = new CountDownLatch(workerNum);
        this.selectors = new Selector[workerNum];
        this.workers = new BlitzServerWorker[workerNum];
        this.listeners = new ServerSocketChannel[workerNum];
        try {
            this.listener = openListener(requestedPort);
            this.listeners[0] = listener;
            this.port = ((InetSocketAddress) listener.getLocalAddress()).getPort();
        } catch (IOException e) {
            throw new IllegalStateException("cannot listen on " + host + ":" + requestedPort + ": "
                    + e.getMessage(), e);
        }
        if (stats) {
            shutdownHook = new Thread(() -> {
                stop();
                try {
                    allFinished.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "blitz-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
    }

    BlitzServerInfo info() {
        return info;
    }

    /**
     * One listening socket per worker needs SO_REUSEPORT: the kernel then hashes a new
     * connection to one of the bound sockets, which spreads keep-alive connections before
     * they are accepted. The shared-listener fallback is used where the option or a second
     * bind is not available.
     */
    private ServerSocketChannel openListener(int bindPort) throws IOException {
        ServerSocketChannel channel = ServerSocketChannel.open();
        try {
            channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            if (reusePort) {
                channel.setOption(StandardSocketOptions.SO_REUSEPORT, true);
            }
            InetSocketAddress address = host == null || host.isEmpty()
                    ? new InetSocketAddress(bindPort)
                    : new InetSocketAddress(host, bindPort);
            channel.bind(address, backlog);
            channel.configureBlocking(false);
            return channel;
        } catch (IOException | RuntimeException e) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // it never listened
            }
            throw e;
        }
    }

    /** Probes for a usable SO_REUSEPORT on this JVM and kernel; bind twice to be sure. */
    static boolean reusePortSupported() {
        if (!Boolean.parseBoolean(System.getProperty("blitz.server.reusePort", "true"))) {
            return false;
        }
        try (ServerSocketChannel first = ServerSocketChannel.open()) {
            first.setOption(StandardSocketOptions.SO_REUSEPORT, true);
            first.bind(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0));
            int probePort = ((InetSocketAddress) first.getLocalAddress()).getPort();
            try (ServerSocketChannel second = ServerSocketChannel.open()) {
                second.setOption(StandardSocketOptions.SO_REUSEPORT, true);
                second.bind(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), probePort));
                return true;
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    boolean reusePort() {
        return reusePort;
    }

    int port() {
        return port;
    }

    int workerNum() {
        return workerNum;
    }

    /** For tests and the stats line: the worker object in one slot, or null before it started. */
    BlitzServerWorker worker(int index) {
        return index >= 0 && index < workers.length ? workers[index] : null;
    }

    int maxBodyBytes() {
        return maxBodyBytes;
    }

    long keepAliveTimeoutMillis() {
        return keepAliveTimeoutMillis;
    }

    int bufferOutputBytes() {
        return bufferOutputBytes;
    }

    Throwable failure() {
        return failure.get();
    }

    /** Primary worker registration: always slot 0. */
    void registerPrimary(BlitzServerWorker worker) {
        worker.setIndex(0);
    }

    /** Secondary registration: slot 1 upward, in the order the scripts reach start(). */
    int registerSecondary(BlitzServerWorker worker) {
        int index = nextIndex.getAndIncrement();
        if (index >= workerNum) {
            throw new IllegalStateException("worker_num=" + workerNum + " but more workers started");
        }
        worker.setIndex(index);
        ready.countDown();
        return index;
    }

    boolean awaitReady(long millis) throws InterruptedException {
        return ready.await(millis, TimeUnit.MILLISECONDS);
    }

    long readyRemaining() {
        return ready.getCount();
    }

    void openGate() {
        gate.countDown();
    }

    void awaitGate() throws InterruptedException {
        gate.await();
    }

    void secondaryFailed(Throwable cause) {
        failure.compareAndSet(null, cause);
        running.set(false);
        gate.countDown();
        ready.countDown();
        closeListener();
        wakeAll();
    }

    private void workerFailed(Throwable cause) {
        failure.compareAndSet(null, cause);
        System.err.println("blitz http: worker failed: " + cause);
        stop();
    }

    /** Runs one worker's selector loop on the calling thread until stop() or a fatal failure. */
    void runWorker(BlitzServerWorker worker) {
        int index = worker.index();
        workers[index] = worker;
        Thread thread = Thread.currentThread();
        String previousName = thread.getName();
        thread.setName("blitz-worker-" + index);
        try {
            Selector selector;
            try {
                selector = Selector.open();
            } catch (IOException e) {
                workerFailed(e);
                if (index != 0) {
                    finished.countDown();
                }
                return;
            }
            selectors[index] = selector;
            ServerSocketChannel acceptChannel;
            try {
                if (index == 0 || !reusePort) {
                    acceptChannel = listener;
                } else {
                    acceptChannel = openListener(port);
                    listeners[index] = acceptChannel;
                }
                acceptChannel.register(selector, SelectionKey.OP_ACCEPT);
            } catch (IOException | RuntimeException e) {
                workerFailed(e);
                closeSelector(selector);
                if (index != 0) {
                    finished.countDown();
                }
                return;
            }
            try {
                worker.installHostRequest();
                BlitzServerState state = worker.state();
                PhpCallable workerStart = state == null ? null : state.workerStartCallback();
                if (workerStart != null) {
                    try {
                        workerStart.call2(worker.php(), null, state.phpObject(), (long) index);
                    } catch (Throwable t) {
                        System.err.println("blitz http: workerStart failed: " + t);
                    }
                }
                long lastIdleCheck = System.nanoTime();
                while (running.get()) {
                    int selected;
                    try {
                        selected = selector.select(1000);
                    } catch (IOException e) {
                        if (running.get()) {
                            workerFailed(e);
                        }
                        break;
                    }
                    if (!running.get()) {
                        break;
                    }
                    if (selected == 0) {
                        long now = System.nanoTime();
                        sweepIdle(selector, now);
                        lastIdleCheck = now;
                        continue;
                    }
                    Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        SelectionKey key = keys.next();
                        keys.remove();
                        if (!key.isValid()) {
                            continue;
                        }
                        try {
                            if (key.isAcceptable()) {
                                accept(selector, worker, acceptChannel);
                            } else {
                                BlitzHttpConnection connection = (BlitzHttpConnection) key.attachment();
                                if (connection == null) {
                                    key.cancel();
                                    continue;
                                }
                                if (key.isReadable()) {
                                    connection.onReadable();
                                }
                                if (key.isValid() && key.isWritable()) {
                                    connection.onWritable();
                                }
                            }
                        } catch (RuntimeException e) {
                            if (running.get()) {
                                System.err.println("blitz http: connection failed: " + e);
                            }
                            key.cancel();
                            Object attachment = key.attachment();
                            if (attachment instanceof BlitzHttpConnection connection) {
                                connection.close();
                            }
                        }
                    }
                    long now = System.nanoTime();
                    if (now - lastIdleCheck >= 1_000_000_000L) {
                        sweepIdle(selector, now);
                        lastIdleCheck = now;
                    }
                }
            } finally {
                worker.uninstallHostRequest();
                BlitzServerState state = worker.state();
                PhpCallable workerStop = state == null ? null : state.workerStopCallback();
                if (workerStop != null) {
                    try {
                        workerStop.call2(worker.php(), null, state.phpObject(), (long) index);
                    } catch (Throwable t) {
                        System.err.println("blitz http: workerStop failed: " + t);
                    }
                }
                closeSelector(selector);
                if (index != 0) {
                    finished.countDown();
                }
            }
        } finally {
            if (stats) {
                System.err.println("blitz http: worker " + index + " requests="
                        + worker.requestsHandled() + " connections=" + worker.connectionsAccepted());
            }
            allFinished.countDown();
            thread.setName(previousName);
            if (index == 0 && shutdownHook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // already shutting down
                }
                shutdownHook = null;
            }
        }
    }

    /** Once a second, closes keep-alive connections that have been quiet for the timeout. */
    private void sweepIdle(Selector selector, long nowNanos) {
        if (keepAliveTimeoutMillis <= 0) {
            return;
        }
        for (SelectionKey key : selector.keys()) {
            if (key.attachment() instanceof BlitzHttpConnection connection) {
                connection.closeIfIdle(nowNanos);
            }
        }
    }

    private void accept(Selector selector, BlitzServerWorker worker,
                        ServerSocketChannel acceptChannel) {
        for (int i = 0; i < 64; i++) {
            SocketChannel channel;
            try {
                channel = acceptChannel.accept();
            } catch (AcceptPendingException e) {
                return;
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("blitz http: accept failed: " + e);
                }
                return;
            }
            if (channel == null) {
                return;
            }
            try {
                channel.configureBlocking(false);
                channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
                worker.countConnection();
                BlitzHttpConnection connection = new BlitzHttpConnection(channel, worker,
                        connectionIds.incrementAndGet(), maxBodyBytes, keepAliveTimeoutMillis,
                        bufferOutputBytes, info.scriptFilename(), info.documentRoot());
                SelectionKey key = channel.register(selector, SelectionKey.OP_READ, connection);
                connection.attach(key);
            } catch (IOException | RuntimeException e) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // it never became a connection
                }
            }
        }
    }

    private void closeSelector(Selector selector) {
        try {
            SelectionKey[] keys = selector.keys().toArray(new SelectionKey[0]);
            for (SelectionKey key : keys) {
                Object attachment = key.attachment();
                if (attachment instanceof BlitzHttpConnection connection) {
                    connection.close();
                }
                key.cancel();
            }
        } catch (RuntimeException ignored) {
            // selector already closed
        }
        try {
            selector.close();
        } catch (IOException ignored) {
            // shutting down
        }
    }

    void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        gate.countDown();
        closeListener();
        wakeAll();
    }

    private void closeListener() {
        for (ServerSocketChannel channel : listeners) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // already closed
                }
            }
        }
    }

    private void wakeAll() {
        for (Selector selector : selectors) {
            if (selector != null) {
                selector.wakeup();
            }
        }
    }

    /** Waits for the secondary workers to leave their loops; called by the primary on exit. */
    void awaitTermination(long millis) {
        try {
            finished.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
