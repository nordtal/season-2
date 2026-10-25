package eu.nordtal.season.bridgemod;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;

/** A WebSocket server on the loopback address that hands each connection's frames to its own Session. */
final class BridgeServer implements AutoCloseable {

    private static final int MAX_FRAME = 1 << 20;

    private final EventLoopGroup group = new MultiThreadIoEventLoopGroup(
            1, new DefaultThreadFactory("nordtal-bridge", true), NioIoHandler.newFactory());
    private final Channel channel;

    /** Binds the port on the loopback address; port 0 takes a free one. */
    BridgeServer(final int port, final Predicate<String> tokens, final Function<Request, CompletableFuture<?>> client)
            throws InterruptedException {
        final ServerBootstrap bootstrap = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel socket) {
                        socket.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(MAX_FRAME))
                                .addLast(new WebSocketServerProtocolHandler("/", null, false, MAX_FRAME))
                                .addLast(new Frames(new Session(tokens, client)));
                    }
                });
        try {
            channel = bootstrap
                    .bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port))
                    .sync()
                    .channel();
        } catch (RuntimeException | InterruptedException e) {
            group.shutdownGracefully().syncUninterruptibly();
            throw e;
        }
    }

    /** The port the server listens on. */
    int port() {
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    @Override
    public void close() {
        channel.close().syncUninterruptibly();
        group.shutdownGracefully().syncUninterruptibly();
    }

    private static final class Frames extends SimpleChannelInboundHandler<TextWebSocketFrame> {

        private final Session session;

        Frames(final Session session) {
            this.session = session;
        }

        @Override
        protected void channelRead0(final ChannelHandlerContext context, final TextWebSocketFrame frame) {
            // Session.receive never fails, so the future carries nothing to look at.
            final var _ = session.receive(frame.text())
                    .thenAccept(reply -> context.executor().execute(() -> {
                        final var written = context.writeAndFlush(new TextWebSocketFrame(reply.text()));
                        if (reply.close()) {
                            written.addListener(_ -> context.writeAndFlush(
                                            new CloseWebSocketFrame(WebSocketCloseStatus.POLICY_VIOLATION))
                                    .addListener(ChannelFutureListener.CLOSE));
                        }
                    }));
        }
    }
}
