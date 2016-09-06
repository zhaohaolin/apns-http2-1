package br.com.zup.push.client;

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
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
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
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.FailedFuture;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.SucceededFuture;

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

import br.com.zup.push.data.PushNotification;
import br.com.zup.push.data.PushResponse;
import br.com.zup.push.proxy.ProxyHandlerFactory;
import br.com.zup.push.util.P12Util;

class Http2Client<T extends PushNotification> {
	
	private static final String							EPOLL_EVENT_LOOP_GROUP_CLASS	= "io.netty.channel.epoll.EpollEventLoopGroup";
	private static final String							EPOLL_SOCKET_CHANNEL_CLASS		= "io.netty.channel.epoll.EpollSocketChannel";
	
	private final Bootstrap								bootstrap;
	private final boolean								shouldShutDownEventLoopGroup;
	private volatile ProxyHandlerFactory				proxyHandlerFactory;
	
	private Long										gracefulShutdownTimeoutMillis;
	
	private volatile ChannelPromise						connectionReadyPromise;
	private volatile ChannelPromise						reconnectionPromise;
	private long										reconnectDelaySeconds			= HttpProperties.INITIAL_RECONNECT_DELAY_SECONDS;
	
	private final Map<T, Promise<PushResponse<T>>>		responsePromises				= new IdentityHashMap<T, Promise<PushResponse<T>>>();
	private final AtomicLong							nextNotificationId				= new AtomicLong(
																								0);
	private ArrayList<String>							identities;
	
	private static final ClientNotConnectedException	NOT_CONNECTED_EXCEPTION			= new ClientNotConnectedException();
	
	private static final Logger							LOG								= LoggerFactory
																								.getLogger(Http2Client.class);
	
	// close future listener
	private class CloseFutureListener implements
			GenericFutureListener<ChannelFuture> {
		
		private final String	host;
		private final int		port;
		
		public CloseFutureListener(String host, int port) {
			this.host = host;
			this.port = port;
		}
		
		@Override
		public void operationComplete(final ChannelFuture future)
				throws Exception {
			synchronized (Http2Client.this.bootstrap) {
				if (Http2Client.this.connectionReadyPromise != null) {
					Http2Client.this.connectionReadyPromise
							.tryFailure(new IllegalStateException(
									"Channel closed before HTTP/2 preface completed."));
					Http2Client.this.connectionReadyPromise = null;
				}
				
				if (Http2Client.this.reconnectionPromise != null) {
					LOG.debug(
							"Disconnected. Next automatic reconnection attempt in {} seconds.",
							Http2Client.this.reconnectDelaySeconds);
					future.channel()
							.eventLoop()
							.schedule(new Runnable() {
								
								@Override
								public void run() {
									LOG.debug("Attempting to reconnect.");
									Http2Client.this.connect(host, port);
								}
							}, Http2Client.this.reconnectDelaySeconds,
									TimeUnit.SECONDS);
					
					Http2Client.this.reconnectDelaySeconds = Math.min(
							Http2Client.this.reconnectDelaySeconds,
							HttpProperties.MAX_RECONNECT_DELAY_SECONDS);
				}
			}
			
			future.channel().eventLoop().submit(new Runnable() {
				
				@Override
				public void run() {
					for (final Promise<PushResponse<T>> responsePromise : Http2Client.this.responsePromises
							.values()) {
						responsePromise
								.tryFailure(new ClientNotConnectedException(
										"Client disconnected unexpectedly."));
					}
					
					Http2Client.this.responsePromises.clear();
				}
			});
		}
	}
	
	// connect future listener
	private class ConnectFutureListener implements
			GenericFutureListener<ChannelFuture> {
		
		@Override
		public void operationComplete(final ChannelFuture future)
				throws Exception {
			if (future.isSuccess()) {
				synchronized (Http2Client.this.bootstrap) {
					if (Http2Client.this.reconnectionPromise != null) {
						LOG.info("Connection to {} restored.", future.channel()
								.remoteAddress());
						Http2Client.this.reconnectionPromise.trySuccess();
					} else {
						LOG.info("Connected to {}.", future.channel()
								.remoteAddress());
					}
					
				}
				
			} else {
				LOG.info("Failed to connect.", future.cause());
			}
			
			Http2Client.this.reconnectDelaySeconds = HttpProperties.INITIAL_RECONNECT_DELAY_SECONDS;
			Http2Client.this.reconnectionPromise = future.channel()
					.newPromise();
		}
		
	}
	
	private class ApplicationProtocolNegotiationHandlerImpl extends
			ApplicationProtocolNegotiationHandler {
		
		protected ApplicationProtocolNegotiationHandlerImpl(
				String fallbackProtocol) {
			super(fallbackProtocol);
		}
		
		@Override
		protected void configurePipeline(final ChannelHandlerContext ctx,
				final String protocol) {
			if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
				final Http2ClientHandlerBuilder<T> builder = new Http2ClientHandlerBuilder<T>();
				final Http2ClientHandler<T> handler = builder
						.server(false)
						.http2Client(Http2Client.this)
						.authority(
								((InetSocketAddress) ctx.channel()
										.remoteAddress()).getHostName())
						.maxUnflushedNotifications(
								HttpProperties.DEFAULT_MAX_UNFLUSHED_NOTIFICATIONS)
						.encoderEnforceMaxConcurrentStreams(true).build();
				
				synchronized (Http2Client.this.bootstrap) {
					if (Http2Client.this.gracefulShutdownTimeoutMillis != null) {
						handler.gracefulShutdownTimeoutMillis(Http2Client.this.gracefulShutdownTimeoutMillis);
					}
				}
				
				ctx.pipeline().addLast(
						new IdleStateHandler(0,
								HttpProperties.DEFAULT_FLUSH_AFTER_IDLE_MILLIS,
								HttpProperties.PING_IDLE_TIME_MILLIS,
								TimeUnit.MILLISECONDS));
				ctx.pipeline().addLast(handler);
				
				ctx.channel().eventLoop().submit(new Runnable() {
					
					@Override
					public void run() {
						final ChannelPromise connectionReadyPromise = Http2Client.this.connectionReadyPromise;
						
						if (connectionReadyPromise != null) {
							connectionReadyPromise.trySuccess();
						}
					}
				});
				
			} else {
				LOG.error("Unexpected protocol: {}", protocol);
				ctx.close();
			}
		}
		
		@Override
		protected void handshakeFailure(final ChannelHandlerContext context,
				final Throwable cause) throws Exception {
			final ChannelPromise connectionReadyPromise = Http2Client.this.connectionReadyPromise;
			
			if (connectionReadyPromise != null) {
				connectionReadyPromise.tryFailure(cause);
			}
			
			super.handshakeFailure(context, cause);
		}
		
	}
	
	public Http2Client(final File p12File, final String password)
			throws IOException, KeyStoreException {
		this(p12File, password, null);
	}
	
	public Http2Client(final File p12File, final String password,
			final EventLoopGroup eventLoopGroup) throws IOException,
			KeyStoreException {
		this(Http2Client.getSslContextWithP12File(p12File, password),
				eventLoopGroup);
		try (final InputStream p12InputStream = new FileInputStream(p12File)) {
			loadIdentifiers(loadKeyStore(p12InputStream, password));
		}
	}
	
	public Http2Client(KeyStore keyStore, final String password)
			throws SSLException {
		this(keyStore, password, null);
	}
	
	public Http2Client(final KeyStore keyStore, final String password,
			final EventLoopGroup eventLoopGroup) throws SSLException {
		this(Http2Client.getSslContextWithP12InputStream(keyStore, password),
				eventLoopGroup);
		loadIdentifiers(keyStore);
	}
	
	public void abortConnection(ErrorResponse errorResponse)
			throws Http2Exception {
		disconnect();
		throw new Http2Exception(Http2Error.CONNECT_ERROR,
				errorResponse.getReason());
		
	}
	
	private static KeyStore loadKeyStore(final InputStream p12InputStream,
			final String password) throws SSLException {
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
	
	public Http2Client(final X509Certificate certificate,
			final PrivateKey privateKey, final String privateKeyPassword)
			throws SSLException {
		this(certificate, privateKey, privateKeyPassword, null);
	}
	
	public Http2Client(final X509Certificate certificate,
			final PrivateKey privateKey, final String privateKeyPassword,
			final EventLoopGroup eventLoopGroup) throws SSLException {
		this(Http2Client.getSslContextWithCertificateAndPrivateKey(certificate,
				privateKey, privateKeyPassword), eventLoopGroup);
	}
	
	private static SslContext getSslContextWithP12File(final File p12File,
			final String password) throws IOException, KeyStoreException {
		try (final InputStream p12InputStream = new FileInputStream(p12File)) {
			return Http2Client.getSslContextWithP12InputStream(
					loadKeyStore(p12InputStream, password), password);
		}
	}
	
	private static SslContext getSslContextWithP12InputStream(
			final KeyStore keyStore, final String password) throws SSLException {
		final X509Certificate x509Certificate;
		final PrivateKey privateKey;
		
		try {
			final PrivateKeyEntry privateKeyEntry = P12Util
					.getFirstPrivateKeyEntryFromP12InputStream(keyStore,
							password);
			
			final Certificate certificate = privateKeyEntry.getCertificate();
			
			if (!(certificate instanceof X509Certificate)) {
				throw new KeyStoreException(
						"Found a certificate in the provided PKCS#12 file, but it was not an X.509 certificate.");
			}
			
			x509Certificate = (X509Certificate) certificate;
			privateKey = privateKeyEntry.getPrivateKey();
		} catch (final KeyStoreException | IOException e) {
			throw new SSLException(e);
		}
		
		return Http2Client.getSslContextWithCertificateAndPrivateKey(
				x509Certificate, privateKey, password);
	}
	
	private static SslContext getSslContextWithCertificateAndPrivateKey(
			final X509Certificate certificate, final PrivateKey privateKey,
			final String privateKeyPassword) throws SSLException {
		return Http2Client.getBaseSslContextBuilder()
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
		SslContextBuilder builder = SslContextBuilder
				.forClient()
				.sslProvider(sslProvider)
				.ciphers(Http2SecurityUtil.CIPHERS,
						SupportedCipherSuiteFilter.INSTANCE)
				.applicationProtocolConfig(
						new ApplicationProtocolConfig(Protocol.ALPN,
								SelectorFailureBehavior.NO_ADVERTISE,
								SelectedListenerFailureBehavior.ACCEPT,
								ApplicationProtocolNames.HTTP_2));
		return builder;
	}
	
	protected Http2Client(final SslContext sslCtx, final EventLoopGroup group) {
		this.bootstrap = new Bootstrap();
		
		if (group != null) {
			this.bootstrap.group(group);
			this.shouldShutDownEventLoopGroup = false;
		} else {
			this.bootstrap.group(new NioEventLoopGroup(1,
					new DefaultThreadFactory("HTTP2APNs")));
			this.shouldShutDownEventLoopGroup = true;
		}
		
		this.bootstrap.channel(this.getSocketChannelClass(this.bootstrap
				.config().group()));
		this.bootstrap.option(ChannelOption.TCP_NODELAY, true);
		this.bootstrap.handler(new ChannelInitializer<SocketChannel>() {
			
			@Override
			protected void initChannel(final SocketChannel channel)
					throws Exception {
				final ChannelPipeline pipeline = channel.pipeline();
				
				// the physical layer
				// logger
				pipeline.addLast("logger", new LoggingHandler(LogLevel.DEBUG));
				
				final ProxyHandlerFactory factory = Http2Client.this.proxyHandlerFactory;
				if (factory != null) {
					pipeline.addFirst(factory.createProxyHandler());
				}
				
				if (HttpProperties.DEFAULT_WRITE_TIMEOUT_MILLIS > 0)
					pipeline.addLast(new WriteTimeoutHandler(
							HttpProperties.DEFAULT_WRITE_TIMEOUT_MILLIS,
							TimeUnit.MILLISECONDS));
				
				pipeline.addLast(sslCtx.newHandler(channel.alloc()));
				pipeline.addLast(new ApplicationProtocolNegotiationHandlerImpl(
						""));
				
				// the application layer
			}
		});
	}
	
	private Class<? extends Channel> getSocketChannelClass(
			final EventLoopGroup eventLoopGroup) {
		if (eventLoopGroup == null) {
			LOG.warn("Asked for socket channel class to work with null event loop group, returning NioSocketChannel class.");
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
				"Don't know which socket channel class to return for event loop group "
						+ className);
	}
	
	private Class<? extends Channel> loadSocketChannelClass(
			final String className) {
		try {
			final Class<?> clazz = Class.forName(className);
			LOG.debug("Loaded socket channel class: {}", clazz);
			return clazz.asSubclass(Channel.class);
		} catch (final ClassNotFoundException e) {
			throw new IllegalArgumentException(e.getMessage(), e);
		}
	}
	
	public void setProxyHandlerFactory(final ProxyHandlerFactory factory) {
		this.proxyHandlerFactory = factory;
		AddressResolverGroup<?> group = (factory == null ? DefaultAddressResolverGroup.INSTANCE
				: NoopAddressResolverGroup.INSTANCE);
		this.bootstrap.resolver(group);
	}
	
	public void setConnectionTimeout(final int timeoutMillis) {
		synchronized (this.bootstrap) {
			this.bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
					timeoutMillis);
		}
	}
	
	public Future<Void> connect(final String host) {
		return this.connect(host, HttpProperties.DEFAULT_APNS_PORT);
	}
	
	public Future<Void> connectSandBox() {
		return this.connect(HttpProperties.DEVELOPMENT_APNS_HOST,
				HttpProperties.DEFAULT_APNS_PORT);
	}
	
	public Future<Void> connectProduction() {
		return this.connect(HttpProperties.PRODUCTION_APNS_HOST,
				HttpProperties.DEFAULT_APNS_PORT);
	}
	
	private Future<Void> connect(final String host, final int port) {
		final Future<Void> connectionReadyFuture;
		
		if (this.bootstrap.config().group().isShuttingDown()
				|| this.bootstrap.config().group().isShutdown()) {
			connectionReadyFuture = new FailedFuture<Void>(
					GlobalEventExecutor.INSTANCE,
					new IllegalStateException(
							"Client's event loop group has been shut down and cannot be restarted."));
			return connectionReadyFuture;
		}
		
		synchronized (this.bootstrap) {
			//
			this.connectionReadyPromise = null;
			final ChannelFuture connectFuture = this.bootstrap.connect(host,
					port);
			this.connectionReadyPromise = connectFuture.channel().newPromise();
			
			connectFuture.channel().closeFuture()
					.addListener(new CloseFutureListener(host, port));
			
			this.connectionReadyPromise
					.addListener(new ConnectFutureListener());
			
			connectionReadyFuture = this.connectionReadyPromise;
		}
		
		return connectionReadyFuture;
	}
	
	public boolean isConnected() {
		final ChannelPromise connectionReadyPromise = this.connectionReadyPromise;
		return (connectionReadyPromise != null && connectionReadyPromise
				.isSuccess());
	}
	
	void waitForInitialSettings() throws InterruptedException {
		this.connectionReadyPromise.channel().pipeline()
				.get(Http2ClientHandler.class).waitForInitialSettings();
	}
	
	public Future<Void> getReconnectionFuture() {
		final Future<Void> reconnectionFuture;
		
		synchronized (this.bootstrap) {
			if (this.isConnected()) {
				reconnectionFuture = this.connectionReadyPromise.channel()
						.newSucceededFuture();
			} else if (this.reconnectionPromise != null) {
				reconnectionFuture = this.reconnectionPromise;
			} else {
				reconnectionFuture = new FailedFuture<Void>(
						GlobalEventExecutor.INSTANCE,
						new IllegalStateException(
								"Client was not previously connected."));
			}
		}
		
		return reconnectionFuture;
	}
	
	public Future<PushResponse<T>> sendNotification(final T notification) {
		final Future<PushResponse<T>> respFuture;
		final long notificationId = this.nextNotificationId.getAndIncrement();
		
		verifyTopic(notification);
		
		final ChannelPromise readyPromise = this.connectionReadyPromise;
		
		if (readyPromise != null && readyPromise.isSuccess()
				&& readyPromise.channel().isActive()) {
			final DefaultPromise<PushResponse<T>> promise = new DefaultPromise<PushResponse<T>>(
					readyPromise.channel().eventLoop());
			
			readyPromise.channel().eventLoop().submit(new Runnable() {
				
				@Override
				public void run() {
					if (Http2Client.this.responsePromises
							.containsKey(notification)) {
						promise.setFailure(new IllegalStateException(
								"The given notification has already been sent and not yet resolved."));
					} else {
						Http2Client.this.responsePromises.put(notification,
								promise);
					}
				}
			});
			
			readyPromise.channel().write(notification)
					.addListener(new GenericFutureListener<ChannelFuture>() {
						
						@Override
						public void operationComplete(final ChannelFuture future)
								throws Exception {
							if (!future.isSuccess()) {
								LOG.debug(
										"Failed to write push notification: {}",
										notification, future.cause());
								
								Http2Client.this.responsePromises
										.remove(notification);
								promise.tryFailure(future.cause());
							}
						}
					});
			
			respFuture = promise;
		} else {
			
			// TODO send failed to processed
			LOG.error(
					"Failed to send push notification because client is not connected: {}",
					notification);
			respFuture = new FailedFuture<PushResponse<T>>(
					GlobalEventExecutor.INSTANCE, NOT_CONNECTED_EXCEPTION);
		}
		
		respFuture
				.addListener(new GenericFutureListener<Future<PushResponse<T>>>() {
					
					@Override
					public void operationComplete(
							final Future<PushResponse<T>> future)
							throws Exception {
						if (future.isSuccess()) {
							final PushResponse<T> response = future.getNow();
						}
					}
				});
		
		return respFuture;
	}
	
	private void verifyTopic(T notification) {
		if (notification.getTopic() == null && this.identities != null
				&& !this.identities.isEmpty()) {
			notification.setTopic(this.identities.get(0));
		}
	}
	
	protected void handlePushNotificationResponse(final PushResponse<T> response) {
		LOG.debug("Received response from APNs gateway: {}", response);
		if (response.getPushNotification() != null) {
			this.responsePromises.remove(response.getPushNotification())
					.setSuccess(response);
		} else {
			this.responsePromises.clear();
		}
	}
	
	public void setGracefulShutdownTimeout(final long timeoutMillis) {
		synchronized (this.bootstrap) {
			this.gracefulShutdownTimeoutMillis = timeoutMillis;
			
			if (this.connectionReadyPromise != null) {
				final Http2ClientHandler<?> handler = this.connectionReadyPromise
						.channel().pipeline().get(Http2ClientHandler.class);
				
				if (handler != null) {
					handler.gracefulShutdownTimeoutMillis(timeoutMillis);
				}
			}
		}
	}
	
	// disconnect
	public Future<Void> disconnect() {
		LOG.info("Disconnecting.");
		final Future<Void> disconnectFuture;
		
		synchronized (this.bootstrap) {
			this.reconnectionPromise = null;
			
			final Future<Void> channelCloseFuture;
			
			if (this.connectionReadyPromise != null) {
				channelCloseFuture = this.connectionReadyPromise.channel()
						.close();
			} else {
				channelCloseFuture = new SucceededFuture<Void>(
						GlobalEventExecutor.INSTANCE, null);
			}
			
			if (this.shouldShutDownEventLoopGroup) {
				channelCloseFuture
						.addListener(new GenericFutureListener<Future<Void>>() {
							
							@Override
							public void operationComplete(
									final Future<Void> future) throws Exception {
								Http2Client.this.bootstrap.config().group()
										.shutdownGracefully();
							}
						});
				
				disconnectFuture = new DefaultPromise<Void>(
						GlobalEventExecutor.INSTANCE);
				
				this.bootstrap.config().group().terminationFuture()
						.addListener(new GenericFutureListener() {
							
							@Override
							public void operationComplete(final Future future)
									throws Exception {
								assert disconnectFuture instanceof DefaultPromise;
								((DefaultPromise<Void>) disconnectFuture)
										.trySuccess(null);
							}
						});
			} else {
				disconnectFuture = channelCloseFuture;
			}
		}
		
		return disconnectFuture;
	}
}
