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
 * The listening socket and the fixed set of worker selector loops.
 *
 * <p>Every worker owns a {@link Selector} and registers the listening channel for accept. A
 * worker that is running PHP is not selecting, so only idle workers race for new connections;
 * a blocking handler therefore stalls its own connections, not the accept path for the whole
 * server. Existing connections on other workers keep being served. There is no acceptor thread
 * and no queue of requests: accept, parse, run the handler, write, all on one worker thread.
 */
final class BlitzServerCore {

    private final BlitzServerInfo info;
    private final int workerNum;
    private final int maxBodyBytes;
    private final ServerSocketChannel listener;
    private final int port;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger nextIndex = new AtomicInteger(1);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicLong connectionIds = new AtomicLong();
    private final CountDownLatch ready;
    private final CountDownLatch gate = new CountDownLatch(1);
    private final CountDownLatch finished;
    private final Selector[] selectors;

    BlitzServerCore(BlitzServerInfo info, String host, int requestedPort,
                    int workerNum, int backlog, int maxBodyBytes) {
        this.info = info;
        this.workerNum = workerNum;
        this.maxBodyBytes = maxBodyBytes;
        this.ready = new CountDownLatch(Math.max(0, workerNum - 1));
        this.finished = new CountDownLatch(Math.max(0, workerNum - 1));
        this.selectors = new Selector[workerNum];
        try {
            this.listener = ServerSocketChannel.open();
            this.listener.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            InetSocketAddress address = host == null || host.isEmpty()
                    ? new InetSocketAddress(requestedPort)
                    : new InetSocketAddress(host, requestedPort);
            this.listener.bind(address, backlog);
            this.listener.configureBlocking(false);
            this.port = ((InetSocketAddress) listener.getLocalAddress()).getPort();
        } catch (IOException e) {
            throw new IllegalStateException("cannot listen on " + host + ":" + requestedPort + ": "
                    + e.getMessage(), e);
        }
    }

    BlitzServerInfo info() {
        return info;
    }

    int port() {
        return port;
    }

    int workerNum() {
        return workerNum;
    }

    int maxBodyBytes() {
        return maxBodyBytes;
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
        try {
            listener.register(selector, SelectionKey.OP_ACCEPT);
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
                            accept(selector, worker);
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
    }

    private void accept(Selector selector, BlitzServerWorker worker) {
        for (int i = 0; i < 64; i++) {
            SocketChannel channel;
            try {
                channel = listener.accept();
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
                BlitzHttpConnection connection = new BlitzHttpConnection(channel, worker,
                        connectionIds.incrementAndGet(), maxBodyBytes, info.scriptFilename(),
                        info.documentRoot());
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
        try {
            listener.close();
        } catch (IOException ignored) {
            // already closed
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
