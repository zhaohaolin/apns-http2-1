package br.com.zup.push.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import br.com.zup.push.data.HttpPushNotification;
import br.com.zup.push.data.PushNotificationResponse;
import br.com.zup.push.proxy.ProxyHandlerFactory;
import br.com.zup.push.util.P12Util;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.socket.oio.OioSocketChannel;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.FailedFuture;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.SucceededFuture;

class PushHttpClient<T extends HttpPushNotification> {
    private static final String EPOLL_EVENT_LOOP_GROUP_CLASS = "io.netty.channel.epoll.EpollEventLoopGroup";
    private static final String EPOLL_SOCKET_CHANNEL_CLASS = "io.netty.channel.epoll.EpollSocketChannel";

    private final Bootstrap bootstrap;
    private final boolean shouldShutDownEventLoopGroup;
    private volatile ProxyHandlerFactory proxyHandlerFactory;

    private Long gracefulShutdownTimeoutMillis;

    private volatile ChannelPromise connectionReadyPromise;
    private volatile ChannelPromise reconnectionPromise;
    private long reconnectDelaySeconds = HttpProperties.INITIAL_RECONNECT_DELAY_SECONDS;

    private final Map<T, Promise<PushNotificationResponse<T>>> responsePromises = new IdentityHashMap<>();
    private final AtomicLong nextNotificationId = new AtomicLong(0);
    private ArrayList<String> identities;

    private static final ClientNotConnectedException NOT_CONNECTED_EXCEPTION = new ClientNotConnectedException();

    private static final Logger log = LoggerFactory.getLogger(PushHttpClient.class);

    public PushHttpClient(final File p12File, final String password) throws IOException, KeyStoreException {
        this(p12File, password, null);
    }

    public PushHttpClient(final File p12File, final String password, final EventLoopGroup eventLoopGroup) throws IOException, KeyStoreException {
        this(PushHttpClient.getSslContextWithP12File(p12File, password), eventLoopGroup);
        try (final InputStream p12InputStream = new FileInputStream(p12File)) {
            loadIdentifiers(loadKeyStore(p12InputStream,password));
        }
    }

    public PushHttpClient(KeyStore keyStore, final String password) throws SSLException {
        this(keyStore, password, null);
    }

    public PushHttpClient(final KeyStore keyStore, final String password, final EventLoopGroup eventLoopGroup) throws SSLException {
        this(PushHttpClient.getSslContextWithP12InputStream(keyStore, password), eventLoopGroup);
        loadIdentifiers(keyStore);
    }

    public void abortConnection(ErrorResponse errorResponse) throws Http2Exception {
        disconnect();
        throw new Http2Exception(Http2Error.CONNECT_ERROR, errorResponse.getReason());

    }

    private static KeyStore loadKeyStore(final InputStream p12InputStream, final String password) throws SSLException {
    	try {
			return P12Util.loadPCKS12KeyStore(p12InputStream, password);
		} catch (KeyStoreException | IOException e) {
            throw new SSLException(e);
		}
    }

    private void loadIdentifiers(KeyStore keyStore) throws SSLException {
    	try {
			this.identities = P12Util.getIdentitiesForP12File(keyStore);
		} catch (KeyStoreException | IOException e) {
            throw new SSLException(e);
		}
    }

    public PushHttpClient(final X509Certificate certificate, final PrivateKey privateKey, final String privateKeyPassword) throws SSLException {
        this(certificate, privateKey, privateKeyPassword, null);
    }

    public PushHttpClient(final X509Certificate certificate, final PrivateKey privateKey, final String privateKeyPassword, final EventLoopGroup eventLoopGroup) throws SSLException {
        this(PushHttpClient.getSslContextWithCertificateAndPrivateKey(certificate, privateKey, privateKeyPassword), eventLoopGroup);
    }

    private static SslContext getSslContextWithP12File(final File p12File, final String password) throws IOException, KeyStoreException {
        try (final InputStream p12InputStream = new FileInputStream(p12File)) {
            return PushHttpClient.getSslContextWithP12InputStream(loadKeyStore(p12InputStream, password), password);
        }
    }

    private static SslContext getSslContextWithP12InputStream(final KeyStore keyStore, final String password) throws SSLException {
        final X509Certificate x509Certificate;
        final PrivateKey privateKey;

        try {
            final PrivateKeyEntry privateKeyEntry = P12Util.getFirstPrivateKeyEntryFromP12InputStream(keyStore, password);
            
            final Certificate certificate = privateKeyEntry.getCertificate();

            if (!(certificate instanceof X509Certificate)) {
                throw new KeyStoreException("Found a certificate in the provided PKCS#12 file, but it was not an X.509 certificate.");
            }

            x509Certificate = (X509Certificate) certificate;
            privateKey = privateKeyEntry.getPrivateKey();
        } catch (final KeyStoreException | IOException e) {
            throw new SSLException(e);
        }

        return PushHttpClient.getSslContextWithCertificateAndPrivateKey(x509Certificate, privateKey, password);
    }

    private static SslContext getSslContextWithCertificateAndPrivateKey(final X509Certificate certificate, final PrivateKey privateKey, final String privateKeyPassword) throws SSLException {
        return PushHttpClient.getBaseSslContextBuilder()
                .keyManager(privateKey, privateKeyPassword, certificate)
                .build();
    }

    private static SslContextBuilder getBaseSslContextBuilder() {
        final SslProvider sslProvider;

        if (OpenSsl.isAvailable()) {
            if (OpenSsl.isAlpnSupported()) {
                sslProvider = SslProvider.OPENSSL;
            } else {
                sslProvider = SslProvider.JDK;
            }
        } else {
            sslProvider = SslProvider.JDK;
        }

        return SslContextBuilder.forClient()
                .sslProvider(sslProvider)
                .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                .applicationProtocolConfig(
                        new ApplicationProtocolConfig(Protocol.ALPN,
                                SelectorFailureBehavior.NO_ADVERTISE,
                                SelectedListenerFailureBehavior.ACCEPT,
                                ApplicationProtocolNames.HTTP_2));
    }

    protected PushHttpClient(final SslContext sslContext, final EventLoopGroup eventLoopGroup) {
        this.bootstrap = new Bootstrap();

        if (eventLoopGroup != null) {
            this.bootstrap.group(eventLoopGroup);
            this.shouldShutDownEventLoopGroup = false;
        } else {
            this.bootstrap.group(new NioEventLoopGroup(1));
            this.shouldShutDownEventLoopGroup = true;
        }

        this.bootstrap.channel(this.getSocketChannelClass(this.bootstrap.config().group()));
        this.bootstrap.option(ChannelOption.TCP_NODELAY, true);
        this.bootstrap.handler(new ChannelInitializer<SocketChannel>() {

            @Override
            protected void initChannel(final SocketChannel channel) throws Exception {
                final ChannelPipeline pipeline = channel.pipeline();

                final ProxyHandlerFactory proxyHandlerFactory = PushHttpClient.this.proxyHandlerFactory;
                if (proxyHandlerFactory != null) {
                    pipeline.addFirst(proxyHandlerFactory.createProxyHandler());
                }

                if (HttpProperties.DEFAULT_WRITE_TIMEOUT_MILLIS > 0)
                    pipeline.addLast(new WriteTimeoutHandler(HttpProperties.DEFAULT_WRITE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));

                pipeline.addLast(sslContext.newHandler(channel.alloc()));
                pipeline.addLast(new ApplicationProtocolNegotiationHandler("") {
                    @Override
                    protected void configurePipeline(final ChannelHandlerContext context, final String protocol) {
                        if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                            final HttpClientHandler<T> HttpClientHandler = new HttpClientHandler.ApnsClientHandlerBuilder<T>()
                                    .server(false)
                                    .apnsClient(PushHttpClient.this)
                                    .authority(((InetSocketAddress) context.channel().remoteAddress()).getHostName())
                                    .maxUnflushedNotifications(HttpProperties.DEFAULT_MAX_UNFLUSHED_NOTIFICATIONS)
                                    .encoderEnforceMaxConcurrentStreams(true)
                                    .build();

                            synchronized (PushHttpClient.this.bootstrap) {
                                if (PushHttpClient.this.gracefulShutdownTimeoutMillis != null) {
                                    HttpClientHandler.gracefulShutdownTimeoutMillis(PushHttpClient.this.gracefulShutdownTimeoutMillis);
                                }
                            }

                            context.pipeline().addLast(new IdleStateHandler(0, HttpProperties.DEFAULT_FLUSH_AFTER_IDLE_MILLIS, HttpProperties.PING_IDLE_TIME_MILLIS, TimeUnit.MILLISECONDS));
                            context.pipeline().addLast(HttpClientHandler);

                            context.channel().eventLoop().submit(new Runnable() {

                                @Override
                                public void run() {
                                    final ChannelPromise connectionReadyPromise = PushHttpClient.this.connectionReadyPromise;

                                    if (connectionReadyPromise != null) {
                                        connectionReadyPromise.trySuccess();
                                    }
                                }
                            });
                        } else {
                            log.error("Unexpected protocol: {}", protocol);
                            context.close();
                        }
                    }

                    @Override
                    protected void handshakeFailure(final ChannelHandlerContext context, final Throwable cause) throws Exception {
                        final ChannelPromise connectionReadyPromise = PushHttpClient.this.connectionReadyPromise;

                        if (connectionReadyPromise != null) {
                            connectionReadyPromise.tryFailure(cause);
                        }

                        super.handshakeFailure(context, cause);
                    }
                });
            }
        });
    }

    private Class<? extends Channel> getSocketChannelClass(final EventLoopGroup eventLoopGroup) {
        if (eventLoopGroup == null) {
            log.warn("Asked for socket channel class to work with null event loop group, returning NioSocketChannel class.");
            return NioSocketChannel.class;
        }

        if (eventLoopGroup instanceof NioEventLoopGroup) {
            return NioSocketChannel.class;
        } else if (eventLoopGroup instanceof OioEventLoopGroup) {
            return OioSocketChannel.class;
        }
        final String className = eventLoopGroup.getClass().getName();
        if (EPOLL_EVENT_LOOP_GROUP_CLASS.equals(className)) {
            return this.loadSocketChannelClass(EPOLL_SOCKET_CHANNEL_CLASS);
        }

        throw new IllegalArgumentException(
                "Don't know which socket channel class to return for event loop group " + className);
    }

    private Class<? extends Channel> loadSocketChannelClass(final String className) {
        try {
            final Class<?> clazz = Class.forName(className);
            log.debug("Loaded socket channel class: {}", clazz);
            return clazz.asSubclass(Channel.class);
        } catch (final ClassNotFoundException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    public void setProxyHandlerFactory(final ProxyHandlerFactory proxyHandlerFactory) {
        this.proxyHandlerFactory = proxyHandlerFactory;
        this.bootstrap.resolver(proxyHandlerFactory == null ? DefaultAddressResolverGroup.INSTANCE : NoopAddressResolverGroup.INSTANCE);
    }

    public void setConnectionTimeout(final int timeoutMillis) {
        synchronized (this.bootstrap) {
            this.bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMillis);
        }
    }

    public Future<Void> connect(final String host) {
        return this.connect(host, HttpProperties.DEFAULT_APNS_PORT);
    }

    public Future<Void> connectSandBox() {
        return this.connect(HttpProperties.DEVELOPMENT_APNS_HOST, HttpProperties.DEFAULT_APNS_PORT);
    }

    public Future<Void> connectProduction() {
        return this.connect(HttpProperties.PRODUCTION_APNS_HOST, HttpProperties.DEFAULT_APNS_PORT);
    }

    public Future<Void> connect(final String host, final int port) {
        final Future<Void> connectionReadyFuture;

        if (this.bootstrap.config().group().isShuttingDown() || this.bootstrap.config().group().isShutdown()) {
            connectionReadyFuture = new FailedFuture<>(GlobalEventExecutor.INSTANCE,
                    new IllegalStateException("Client's event loop group has been shut down and cannot be restarted."));
        } else {
            synchronized (this.bootstrap) {
                if (this.connectionReadyPromise == null) {

                    final ChannelFuture connectFuture = this.bootstrap.connect(host, port);
                    this.connectionReadyPromise = connectFuture.channel().newPromise();

                    connectFuture.channel().closeFuture().addListener(new GenericFutureListener<ChannelFuture> () {

                        @Override
                        public void operationComplete(final ChannelFuture future) throws Exception {
                            synchronized (PushHttpClient.this.bootstrap) {
                                if (PushHttpClient.this.connectionReadyPromise != null) {
                                    PushHttpClient.this.connectionReadyPromise.tryFailure(
                                            new IllegalStateException("Channel closed before HTTP/2 preface completed."));

                                    PushHttpClient.this.connectionReadyPromise = null;
                                }

                                if (PushHttpClient.this.reconnectionPromise != null) {
                                    log.debug("Disconnected. Next automatic reconnection attempt in {} seconds.", PushHttpClient.this.reconnectDelaySeconds);
                                    future.channel().eventLoop().schedule(new Runnable() {

                                        @Override
                                        public void run() {
                                            log.debug("Attempting to reconnect.");
                                            PushHttpClient.this.connect(host, port);
                                        }
                                    }, PushHttpClient.this.reconnectDelaySeconds, TimeUnit.SECONDS);

                                    PushHttpClient.this.reconnectDelaySeconds = Math.min(PushHttpClient.this.reconnectDelaySeconds, HttpProperties.MAX_RECONNECT_DELAY_SECONDS);
                                }
                            }

                            future.channel().eventLoop().submit(new Runnable() {

                                @Override
                                public void run() {
                                    for (final Promise<PushNotificationResponse<T>> responsePromise : PushHttpClient.this.responsePromises.values()) {
                                        responsePromise.tryFailure(new ClientNotConnectedException("Client disconnected unexpectedly."));
                                    }

                                    PushHttpClient.this.responsePromises.clear();
                                }
                            });
                        }
                    });

                    this.connectionReadyPromise.addListener(new GenericFutureListener<ChannelFuture>() {

                        @Override
                        public void operationComplete(final ChannelFuture future) throws Exception {
                            if (future.isSuccess()) {
                                synchronized (PushHttpClient.this.bootstrap) {
                                    if (PushHttpClient.this.reconnectionPromise != null) {
                                        log.info("Connection to {} restored.", future.channel().remoteAddress());
                                        PushHttpClient.this.reconnectionPromise.trySuccess();
                                    } else {
                                        log.info("Connected to {}.", future.channel().remoteAddress());
                                    }

                                    PushHttpClient.this.reconnectDelaySeconds = HttpProperties.INITIAL_RECONNECT_DELAY_SECONDS;
                                    PushHttpClient.this.reconnectionPromise = future.channel().newPromise();
                                }

                            } else {
                                log.info("Failed to connect.", future.cause());
                            }
                        }
                    });
                }

                connectionReadyFuture = this.connectionReadyPromise;
            }
        }

        return connectionReadyFuture;
    }

    public boolean isConnected() {
        final ChannelPromise connectionReadyPromise = this.connectionReadyPromise;
        return (connectionReadyPromise != null && connectionReadyPromise.isSuccess());
    }

    void waitForInitialSettings() throws InterruptedException {
        this.connectionReadyPromise.channel().pipeline().get(HttpClientHandler.class).waitForInitialSettings();
    }

    public Future<Void> getReconnectionFuture() {
        final Future<Void> reconnectionFuture;

        synchronized (this.bootstrap) {
            if (this.isConnected()) {
                reconnectionFuture = this.connectionReadyPromise.channel().newSucceededFuture();
            } else if (this.reconnectionPromise != null) {
                reconnectionFuture = this.reconnectionPromise;
            } else {
                reconnectionFuture = new FailedFuture<>(GlobalEventExecutor.INSTANCE,
                        new IllegalStateException("Client was not previously connected."));
            }
        }

        return reconnectionFuture;
    }

    public Future<PushNotificationResponse<T>> sendNotification(final T notification) {
        final Future<PushNotificationResponse<T>> responseFuture;
        final long notificationId = this.nextNotificationId.getAndIncrement();
        
        verifyTopic(notification);

        final ChannelPromise connectionReadyPromise = this.connectionReadyPromise;

        if (connectionReadyPromise != null && connectionReadyPromise.isSuccess() && connectionReadyPromise.channel().isActive()) {
            final DefaultPromise<PushNotificationResponse<T>> responsePromise =
                    new DefaultPromise<>(connectionReadyPromise.channel().eventLoop());

            connectionReadyPromise.channel().eventLoop().submit(new Runnable() {

                @Override
                public void run() {
                    if (PushHttpClient.this.responsePromises.containsKey(notification)) {
                        responsePromise.setFailure(new IllegalStateException(
                                "The given notification has already been sent and not yet resolved."));
                    } else {
                        PushHttpClient.this.responsePromises.put(notification, responsePromise);
                    }
                }
            });

            connectionReadyPromise.channel().write(notification).addListener(new GenericFutureListener<ChannelFuture>() {

                @Override
                public void operationComplete(final ChannelFuture future) throws Exception {
                    if (!future.isSuccess()) {
                        log.debug("Failed to write push notification: {}", notification, future.cause());

                        PushHttpClient.this.responsePromises.remove(notification);
                        responsePromise.tryFailure(future.cause());
                    }
                }
            });

            responseFuture = responsePromise;
        } else {
            log.debug("Failed to send push notification because client is not connected: {}", notification);
            responseFuture = new FailedFuture<>(
                    GlobalEventExecutor.INSTANCE, NOT_CONNECTED_EXCEPTION);
        }

        responseFuture.addListener(new GenericFutureListener<Future<PushNotificationResponse<T>>>() {

            @Override
            public void operationComplete(final Future<PushNotificationResponse<T>> future) throws Exception {
                if (future.isSuccess()) {
                    final PushNotificationResponse<T> response = future.getNow();
                }
            }
        });

        return responseFuture;
    }

    private void verifyTopic(T notification) {
    	if(notification.getTopic() == null 
    			&& this.identities != null
    			&& !this.identities.isEmpty()) {
    		notification.setTopic(this.identities.get(0));
    	}
	}

	protected void handlePushNotificationResponse(final PushNotificationResponse<T> response) {
        log.debug("Received response from APNs gateway: {}", response);
        if (response.getPushNotification() != null){
            this.responsePromises.remove(response.getPushNotification()).setSuccess(response);
        } else {
            this.responsePromises.clear();
        }
    }

    public void setGracefulShutdownTimeout(final long timeoutMillis) {
        synchronized (this.bootstrap) {
            this.gracefulShutdownTimeoutMillis = timeoutMillis;

            if (this.connectionReadyPromise != null) {
                final HttpClientHandler handler = this.connectionReadyPromise.channel().pipeline().get(HttpClientHandler.class);

                if (handler != null) {
                    handler.gracefulShutdownTimeoutMillis(timeoutMillis);
                }
            }
        }
    }

    public Future<Void> disconnect() {
        log.info("Disconnecting.");
        final Future<Void> disconnectFuture;

        synchronized (this.bootstrap) {
            this.reconnectionPromise = null;

            final Future<Void> channelCloseFuture;

            if (this.connectionReadyPromise != null) {
                channelCloseFuture = this.connectionReadyPromise.channel().close();
            } else {
                channelCloseFuture = new SucceededFuture<>(GlobalEventExecutor.INSTANCE, null);
            }

            if (this.shouldShutDownEventLoopGroup) {
                channelCloseFuture.addListener(new GenericFutureListener<Future<Void>>() {

                    @Override
                    public void operationComplete(final Future<Void> future) throws Exception {
                        PushHttpClient.this.bootstrap.config().group().shutdownGracefully();
                    }
                });

                disconnectFuture = new DefaultPromise<>(GlobalEventExecutor.INSTANCE);

                this.bootstrap.config().group().terminationFuture().addListener(new GenericFutureListener() {

                    @Override
                    public void operationComplete(final Future future) throws Exception {
                        assert disconnectFuture instanceof DefaultPromise;
                        ((DefaultPromise<Void>) disconnectFuture).trySuccess(null);
                    }
                });
            } else {
                disconnectFuture = channelCloseFuture;
            }
        }

        return disconnectFuture;
    }
}