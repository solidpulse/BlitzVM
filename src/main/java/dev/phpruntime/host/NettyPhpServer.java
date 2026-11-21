package dev.phpruntime.host;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeadersFactory;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeadersFactory;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Netty front end (native epoll on Linux, NIO elsewhere).
 *
 * <p>Event-loop threads only parse and write HTTP; they never run PHP. Each request
 * is handed to a worker thread, which blocks on the scheduler for a CPU slot and runs
 * the script, then writes the response back through the channel (Netty hops to the
 * event loop for the actual write). Each connection runs one request at a time from a
 * FIFO, so pipelined HTTP/1.1 requests are answered in order; while a request is in
 * flight the connection also stops reading, so a client cannot queue unbounded work.
 */
public final class NettyPhpServer implements PhpServer {

    private static final int MAX_BODY = 8 * 1024 * 1024;

    /**
     * Response headers are built without Netty's name and value validation. PHP's header()
     * and setcookie() reject CR, LF and NUL themselves (see HttpFunctions.checkedHeaderLine),
     * and php allows other control bytes (tab, 0x01, DEL) in a value, which Netty would reject.
     * Both factories are immutable and shareable between event loops.
     */
    private static final HttpHeadersFactory RESPONSE_HEADERS =
            DefaultHttpHeadersFactory.headersFactory().withValidation(false);
    private static final HttpHeadersFactory RESPONSE_TRAILERS =
            DefaultHttpHeadersFactory.trailersFactory().withValidation(false);

    private final PhpDispatcher dispatcher;
    private final int requestedPort;
    private final ExecutorService workers;
    private final EventLoopGroup boss;
    private final EventLoopGroup io;
    private final Class<? extends ServerChannel> channelType;
    private Channel channel;

    public NettyPhpServer(PhpDispatcher dispatcher, int port, int maxConcurrentRequests) {
        this.dispatcher = dispatcher;
        this.requestedPort = port;
        this.workers = Executors.newFixedThreadPool(maxConcurrentRequests, r -> {
            Thread t = new Thread(r, "php-worker");
            t.setDaemon(true);
            return t;
        });
        boolean epoll = Epoll.isAvailable();
        IoHandlerFactory factory = epoll ? EpollIoHandler.newFactory() : NioIoHandler.newFactory();
        this.boss = new MultiThreadIoEventLoopGroup(1, factory);
        this.io = new MultiThreadIoEventLoopGroup(Math.max(2, Runtime.getRuntime().availableProcessors() / 2), factory);
        this.channelType = epoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
    }

    public String transport() {
        return channelType == EpollServerSocketChannel.class ? "netty-epoll" : "netty-nio";
    }

    @Override
    public void start() throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap()
                .group(boss, io)
                .channel(channelType)
                .option(ChannelOption.SO_BACKLOG, 4096)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(MAX_BODY))
                                .addLast(new Handler());
                    }
                });
        channel = b.bind(requestedPort).sync().channel();
    }

    @Override
    public int port() {
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    /** One per connection. Its fields are only touched on the connection's event loop. */
    private final class Handler extends SimpleChannelInboundHandler<FullHttpRequest> {

        private record Pending(PhpDispatcher.Request request, io.netty.handler.codec.http.HttpVersion version, boolean keepAlive) {
        }

        private final java.util.ArrayDeque<Pending> queue = new java.util.ArrayDeque<>();
        private boolean busy;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
            boolean keepAlive = HttpUtil.isKeepAlive(msg);
            String uri = msg.uri();
            int q = uri.indexOf('?');
            Map<String, String> headers = new LinkedHashMap<>();
            for (Map.Entry<String, String> h : msg.headers()) {
                headers.merge(h.getKey(), h.getValue(), (a, b) -> a + ", " + b);
            }
            InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
            InetSocketAddress local = (InetSocketAddress) ctx.channel().localAddress();
            PhpDispatcher.Request req = new PhpDispatcher.Request(
                    msg.method().name(),
                    q < 0 ? uri : uri.substring(0, q),
                    q < 0 ? null : uri.substring(q + 1),
                    msg.protocolVersion().text(),
                    headers,
                    ByteBufUtil.getBytes(msg.content()),
                    remote.getAddress().getHostAddress(),
                    local.getHostString(),
                    local.getPort());
            queue.add(new Pending(req, msg.protocolVersion(), keepAlive));
            ctx.channel().config().setAutoRead(false);
            if (!busy) {
                dispatchNext(ctx);
            }
        }

        private void dispatchNext(ChannelHandlerContext ctx) {
            Pending p = queue.poll();
            if (p == null) {
                busy = false;
                ctx.channel().config().setAutoRead(true);
                return;
            }
            busy = true;
            workers.execute(() -> {
                PhpDispatcher.Response res = dispatcher.handle(p.request());
                FullHttpResponse out = new DefaultFullHttpResponse(p.version(),
                        HttpResponseStatus.valueOf(res.status()), Unpooled.wrappedBuffer(res.body()),
                        RESPONSE_HEADERS, RESPONSE_TRAILERS);
                for (String[] h : res.headers()) {
                    out.headers().add(h[0], h[1]);
                }
                out.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, res.body().length);
                if (p.keepAlive()) {
                    out.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                    // The listener runs on the event loop, which owns the queue.
                    ctx.writeAndFlush(out).addListener((ChannelFutureListener) f -> dispatchNext(ctx));
                } else {
                    ctx.writeAndFlush(out).addListener(ChannelFutureListener.CLOSE);
                }
            });
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    @Override
    public void close() {
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
        boss.shutdownGracefully();
        io.shutdownGracefully();
        workers.shutdownNow();
    }
}
