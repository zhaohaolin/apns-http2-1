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

import br.com.zup.push.client.HttpClientHandler.ApnsClientHandlerBuilder;
import br.com.zup.push.data.PushNotification;
import br.com.zup.push.data.PushResponse;
import br.com.zup.push.proxy.ProxyHandlerFactory;
import br.com.zup.push.util.P12Util;

class APNsHttp2Client<T extends PushNotification> {
	
	private static final String							EPOLL_EVENT_LOOP_GROUP_CLASS	= "io.netty.channel.epoll.EpollEventLoopGroup";
	private static final String							EPOLL_SOCKET_CHANNEL_CLASS		= "io.netty.channel.epoll.EpollSocketChannel";
	
	private final Bootstrap								boot;
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
	
	private static final Logger							log								= LoggerFactory
																								.getLogger(APNsHttp2Client.class);
	
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
			synchronized (APNsHttp2Client.this.boot) {
				if (APNsHttp2Client.this.connectionReadyPromise != null) {
					APNsHttp2Client.this.connectionReadyPromise
							.tryFailure(new IllegalStateException(
									"Channel closed before HTTP/2 preface completed."));
					APNsHttp2Client.this.connectionReadyPromise = null;
				}
				
				if (APNsHttp2Client.this.reconnectionPromise != null) {
					log.debug(
							"Disconnected. Next automatic reconnection attempt in {} seconds.",
							APNsHttp2Client.this.reconnectDelaySeconds);
					future.channel()
							.eventLoop()
							.schedule(new Runnable() {
								
								@Override
								public void run() {
									log.debug("Attempting to reconnect.");
									APNsHttp2Client.this.connect(host, port);
								}
							}, APNsHttp2Client.this.reconnectDelaySeconds,
									TimeUnit.SECONDS);
					
					APNsHttp2Client.this.reconnectDelaySeconds = Math.min(
							APNsHttp2Client.this.reconnectDelaySeconds,
							HttpProperties.MAX_RECONNECT_DELAY_SECONDS);
				}
			}
			
			future.channel().eventLoop().submit(new Runnable() {
				
				@Override
				public void run() {
					for (final Promise<PushResponse<T>> responsePromise : APNsHttp2Client.this.responsePromises
							.values()) {
						responsePromise
								.tryFailure(new ClientNotConnectedException(
										"Client disconnected unexpectedly."));
					}
					
					APNsHttp2Client.this.responsePromises.clear();
				}
			});
		}
	}
	
	private class ConnectFutureListener implements
			GenericFutureListener<ChannelFuture> {
		
		@Override
		public void operationComplete(final ChannelFuture future)
				throws Exception {
			if (future.isSuccess()) {
				synchronized (APNsHttp2Client.this.boot) {
					if (APNsHttp2Client.this.reconnectionPromise != null) {
						log.info("Connection to {} restored.", future.channel()
								.remoteAddress());
						APNsHttp2Client.this.reconnectionPromise.trySuccess();
					} else {
						log.info("Connected to {}.", future.channel()
								.remoteAddress());
					}
					
					APNsHttp2Client.this.reconnectDelaySeconds = HttpProperties.INITIAL_RECONNECT_DELAY_SECONDS;
					APNsHttp2Client.this.reconnectionPromise = future.channel()
							.newPromise();
				}
				
			} else {
				log.info("Failed to connect.", future.cause());
			}
		}
		
	}
	
	public APNsHttp2Client(final File p12File, final String password)
			throws IOException, KeyStoreException {
		this(p12File, password, null);
	}
	
	public APNsHttp2Client(final File p12File, final String password,
			final EventLoopGroup eventLoopGroup) throws IOException,
			KeyStoreException {
		this(APNsHttp2Client.getSslContextWithP12File(p12File, password),
				eventLoopGroup);
		try (final InputStream p12InputStream = new FileInputStream(p12File)) {
			loadIdentifiers(loadKeyStore(p12InputStream, password));
		}
	}
	
	public APNsHttp2Client(KeyStore keyStore, final String password)
			throws SSLException {
		this(keyStore, password, null);
	}
	
	public APNsHttp2Client(final KeyStore keyStore, final String password,
			final EventLoopGroup eventLoopGroup) throws SSLException {
		this(APNsHttp2Client
				.getSslContextWithP12InputStream(keyStore, password),
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
	
	public APNsHttp2Client(final X509Certificate certificate,
			final PrivateKey privateKey, final String privateKeyPassword)
			throws SSLException {
		this(certificate, privateKey, privateKeyPassword, null);
	}
	
	public APNsHttp2Client(final X509Certificate certificate,
			final PrivateKey privateKey, final String privateKeyPassword,
			final EventLoopGroup eventLoopGroup) throws SSLException {
		this(APNsHttp2Client.getSslContextWithCertificateAndPrivateKey(
				certificate, privateKey, privateKeyPassword), eventLoopGroup);
	}
	
	private static SslContext getSslContextWithP12File(final File p12File,
			final String password) throws IOException, KeyStoreException {
		try (final InputStream p12InputStream = new FileInputStream(p12File)) {
			return APNsHttp2Client.getSslContextWithP12InputStream(
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
		
		return APNsHttp2Client.getSslContextWithCertificateAndPrivateKey(
				x509Certificate, privateKey, password);
	}
	
	private static SslContext getSslContextWithCertificateAndPrivateKey(
			final X509Certificate certificate, final PrivateKey privateKey,
			final String privateKeyPassword) throws SSLException {
		return APNsHttp2Client.getBaseSslContextBuilder()
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
		
		return SslContextBuilder
				.forClient()
				.sslProvider(sslProvider)
				.ciphers(Http2SecurityUtil.CIPHERS,
						SupportedCipherSuiteFilter.INSTANCE)
				.applicationProtocolConfig(
						new ApplicationProtocolConfig(Protocol.ALPN,
								SelectorFailureBehavior.NO_ADVERTISE,
								SelectedListenerFailureBehavior.ACCEPT,
								ApplicationProtocolNames.HTTP_2));
	}
	
	protected APNsHttp2Client(final SslContext sslCtx,
			final EventLoopGroup group) {
		this.boot = new Bootstrap();
		
		if (group != null) {
			this.boot.group(group);
			this.shouldShutDownEventLoopGroup = false;
		} else {
			this.boot.group(new NioEventLoopGroup(1));
			this.shouldShutDownEventLoopGroup = true;
		}
		
		this.boot.channel(this
				.getSocketChannelClass(this.boot.config().group()));
		this.boot.option(ChannelOption.TCP_NODELAY, true);
		this.boot.handler(new ChannelInitializer<SocketChannel>() {
			
			@Override
			protected void initChannel(final SocketChannel channel)
					throws Exception {
				final ChannelPipeline pipeline = channel.pipeline();
				
				final ProxyHandlerFactory factory = APNsHttp2Client.this.proxyHandlerFactory;
				if (factory != null) {
					pipeline.addFirst(factory.createProxyHandler());
				}
				
				if (HttpProperties.DEFAULT_WRITE_TIMEOUT_MILLIS > 0)
					pipeline.addLast(new WriteTimeoutHandler(
							HttpProperties.DEFAULT_WRITE_TIMEOUT_MILLIS,
							TimeUnit.MILLISECONDS));
				
				pipeline.addLast(sslCtx.newHandler(channel.alloc()));
				pipeline.addLast(new ApplicationProtocolNegotiationHandler("") {
					@Override
					protected void configurePipeline(
							final ChannelHandlerContext ctx,
							final String protocol) {
						if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
							final ApnsClientHandlerBuilder<T> builder = new ApnsClientHandlerBuilder<T>();
							final HttpClientHandler<T> handler = builder
									.server(false)
									.apnsClient(APNsHttp2Client.this)
									.authority(
											((InetSocketAddress) ctx.channel()
													.remoteAddress())
													.getHostName())
									.maxUnflushedNotifications(
											HttpProperties.DEFAULT_MAX_UNFLUSHED_NOTIFICATIONS)
									.encoderEnforceMaxConcurrentStreams(true)
									.build();
							
							synchronized (APNsHttp2Client.this.boot) {
								if (APNsHttp2Client.this.gracefulShutdownTimeoutMillis != null) {
									handler.gracefulShutdownTimeoutMillis(APNsHttp2Client.this.gracefulShutdownTimeoutMillis);
								}
							}
							
							ctx.pipeline()
									.addLast(
											new IdleStateHandler(
													0,
													HttpProperties.DEFAULT_FLUSH_AFTER_IDLE_MILLIS,
													HttpProperties.PING_IDLE_TIME_MILLIS,
													TimeUnit.MILLISECONDS));
							ctx.pipeline().addLast(handler);
							
							ctx.channel().eventLoop().submit(new Runnable() {
								
								@Override
								public void run() {
									final ChannelPromise connectionReadyPromise = APNsHttp2Client.this.connectionReadyPromise;
									
									if (connectionReadyPromise != null) {
										connectionReadyPromise.trySuccess();
									}
								}
							});
							
						} else {
							log.error("Unexpected protocol: {}", protocol);
							ctx.close();
						}
					}
					
					@Override
					protected void handshakeFailure(
							final ChannelHandlerContext context,
							final Throwable cause) throws Exception {
						final ChannelPromise connectionReadyPromise = APNsHttp2Client.this.connectionReadyPromise;
						
						if (connectionReadyPromise != null) {
							connectionReadyPromise.tryFailure(cause);
						}
						
						super.handshakeFailure(context, cause);
					}
				});
			}
		});
	}
	
	private Class<? extends Channel> getSocketChannelClass(
			final EventLoopGroup eventLoopGroup) {
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
				"Don't know which socket channel class to return for event loop group "
						+ className);
	}
	
	private Class<? extends Channel> loadSocketChannelClass(
			final String className) {
		try {
			final Class<?> clazz = Class.forName(className);
			log.debug("Loaded socket channel class: {}", clazz);
			return clazz.asSubclass(Channel.class);
		} catch (final ClassNotFoundException e) {
			throw new IllegalArgumentException(e.getMessage(), e);
		}
	}
	
	public void setProxyHandlerFactory(final ProxyHandlerFactory factory) {
		this.proxyHandlerFactory = factory;
		AddressResolverGroup<?> group = (factory == null ? DefaultAddressResolverGroup.INSTANCE
				: NoopAddressResolverGroup.INSTANCE);
		this.boot.resolver(group);
	}
	
	public void setConnectionTimeout(final int timeoutMillis) {
		synchronized (this.boot) {
			this.boot.option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
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
	
	public Future<Void> connect(final String host, final int port) {
		final Future<Void> connectionReadyFuture;
		
		if (this.boot.config().group().isShuttingDown()
				|| this.boot.config().group().isShutdown()) {
			connectionReadyFuture = new FailedFuture<Void>(
					GlobalEventExecutor.INSTANCE,
					new IllegalStateException(
							"Client's event loop group has been shut down and cannot be restarted."));
		} else {
			synchronized (this.boot) {
				if (this.connectionReadyPromise == null) {
					
					final ChannelFuture connectFuture = this.boot.connect(host,
							port);
					this.connectionReadyPromise = connectFuture.channel()
							.newPromise();
					
					connectFuture.channel().closeFuture()
							.addListener(new CloseFutureListener(host, port));
					
					this.connectionReadyPromise
							.addListener(new ConnectFutureListener());
				}
				
				connectionReadyFuture = this.connectionReadyPromise;
			}
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
				.get(HttpClientHandler.class).waitForInitialSettings();
	}
	
	public Future<Void> getReconnectionFuture() {
		final Future<Void> reconnectionFuture;
		
		synchronized (this.boot) {
			if (this.isConnected()) {
				reconnectionFuture = this.connectionReadyPromise.channel()
						.newSucceededFuture();
			} else if (this.reconnectionPromise != null) {
				reconnectionFuture = this.reconnectionPromise;
			} else {
				reconnectionFuture = new FailedFuture<>(
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
					if (APNsHttp2Client.this.responsePromises
							.containsKey(notification)) {
						promise.setFailure(new IllegalStateException(
								"The given notification has already been sent and not yet resolved."));
					} else {
						APNsHttp2Client.this.responsePromises.put(notification,
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
								log.debug(
										"Failed to write push notification: {}",
										notification, future.cause());
								
								APNsHttp2Client.this.responsePromises
										.remove(notification);
								promise.tryFailure(future.cause());
							}
						}
					});
			
			respFuture = promise;
		} else {
			// TODO send failed to processed 
			log.error(
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
		log.debug("Received response from APNs gateway: {}", response);
		if (response.getPushNotification() != null) {
			this.responsePromises.remove(response.getPushNotification())
					.setSuccess(response);
		} else {
			this.responsePromises.clear();
		}
	}
	
	public void setGracefulShutdownTimeout(final long timeoutMillis) {
		synchronized (this.boot) {
			this.gracefulShutdownTimeoutMillis = timeoutMillis;
			
			if (this.connectionReadyPromise != null) {
				final HttpClientHandler<?> handler = this.connectionReadyPromise
						.channel().pipeline().get(HttpClientHandler.class);
				
				if (handler != null) {
					handler.gracefulShutdownTimeoutMillis(timeoutMillis);
				}
			}
		}
	}
	
	public Future<Void> disconnect() {
		log.info("Disconnecting.");
		final Future<Void> disconnectFuture;
		
		synchronized (this.boot) {
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
								APNsHttp2Client.this.boot.config().group()
										.shutdownGracefully();
							}
						});
				
				disconnectFuture = new DefaultPromise<>(
						GlobalEventExecutor.INSTANCE);
				
				this.boot.config().group().terminationFuture()
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
