package br.com.zup.push.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.GenericFutureListener;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import br.com.zup.push.data.APNsCallBack;
import br.com.zup.push.data.DefaultPushResponse;
import br.com.zup.push.data.PushNotification;
import br.com.zup.push.data.PushResponse;
import br.com.zup.push.proxy.ProxyHandlerFactory;
import br.com.zup.push.util.P12Util;

public class Http2Client {
	
	// private static class fields:
	private static final Logger							LOG					= LoggerFactory
																					.getLogger(Http2Client.class);
	private static final ScheduledExecutorService		exec				= Executors
																					.newSingleThreadScheduledExecutor(new DefaultThreadFactory(
																							"APNsSession"));
	
	// private final:
	private final Bootstrap								bootstrap;
	private final Map<PushNotification, APNsCallBack>	responsePromises	= new IdentityHashMap<PushNotification, APNsCallBack>();
	
	// private volatile：
	private volatile ProxyHandlerFactory				proxyHandlerFactory;
	
	private volatile ScheduledFuture<?>					lunchConnectFuture;
	
	// private:
	private Long										gracefulShutdownTimeoutMillis;
	private ArrayList<String>							identities;
	private String										name				= "";
	private long										reconnectTimeout	= HttpProperties.INITIAL_RECONNECT_DELAY_SECONDS;
	
	private String										host				= HttpProperties.PRODUCTION_APNS_HOST;
	private int											port				= 443;
	
	// session
	private int											maxSession			= 1;
	private List<Channel>								sessionStore		= new CopyOnWriteArrayList<Channel>();
	private AtomicInteger								sessionIdx			= new AtomicInteger(
																					0);
	private AtomicBoolean								stopped				= new AtomicBoolean(
																					false);
	private int											threads				= Runtime
																					.getRuntime()
																					.availableProcessors() * 2;
	private EventLoopGroup								group				= new NioEventLoopGroup(
																					threads,
																					new DefaultThreadFactory(
																							"HTTP2APNs"));
	
	private class ProtocolNegotiationHandlerImpl extends
			ApplicationProtocolNegotiationHandler {
		
		protected ProtocolNegotiationHandlerImpl(String fallbackProtocol) {
			super(fallbackProtocol);
		}
		
		@Override
		protected void configurePipeline(final ChannelHandlerContext ctx,
				final String protocol) {
			if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
				final Http2ClientHandlerBuilder builder = new Http2ClientHandlerBuilder();
				final Http2ClientHandler handler = builder
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
				
			} else {
				LOG.error(name + "-> Unexpected protocol: {}", protocol);
				ctx.close();
			}
		}
		
		@Override
		protected void handshakeFailure(final ChannelHandlerContext context,
				final Throwable cause) throws Exception {
			//
			super.handshakeFailure(context, cause);
		}
		
		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
				throws Exception {
			LOG.warn("{} Failed to select the application-level protocol:",
					ctx.channel(), cause);
			ctx.close();
		}
		
	}
	
	private void loadIdentifiers(KeyStore keyStore) throws KeyStoreException,
			IOException {
		try {
			this.identities = P12Util.getIdentitiesForP12File(keyStore);
		} catch (KeyStoreException e) {
			throw e;
		} catch (IOException e) {
			throw e;
		}
	}
	
	private void verifyTopic(PushNotification notification) {
		if (notification.getTopic() == null && this.identities != null
				&& !this.identities.isEmpty()) {
			notification.setTopic(this.identities.get(0));
		}
	}
	
	public Http2Client(final File p12File, final String password,
			final boolean sandboxEnvironment, int maxSession)
			throws IOException, KeyStoreException {
		this(p12File, password, null, sandboxEnvironment, maxSession);
	}
	
	public Http2Client(final File p12File, final String password,
			final EventLoopGroup eventLoopGroup,
			final boolean sandboxEnvironment, int maxSession)
			throws IOException, KeyStoreException {
		this(SslUtils.getSslContextWithP12File(p12File, password),
				eventLoopGroup, sandboxEnvironment, maxSession);
		try (final InputStream p12InputStream = new FileInputStream(p12File)) {
			loadIdentifiers(SslUtils.loadKeyStore(p12InputStream, password));
			if (null != this.identities && !this.identities.isEmpty()) {
				name = this.identities.get(0);
			}
		}
	}
	
	public Http2Client(KeyStore keyStore, final String password,
			final boolean sandboxEnvironment, int maxSession)
			throws KeyStoreException, IOException {
		this(keyStore, password, null, sandboxEnvironment, maxSession);
	}
	
	public Http2Client(final KeyStore keyStore, final String password,
			final EventLoopGroup eventLoopGroup,
			final boolean sandboxEnvironment, int maxSession)
			throws KeyStoreException, IOException {
		this(SslUtils.getSslContextWithP12InputStream(keyStore, password),
				eventLoopGroup, sandboxEnvironment, maxSession);
		loadIdentifiers(keyStore);
		if (null != this.identities && !this.identities.isEmpty()) {
			name = this.identities.get(0);
		}
	}
	
	public void abortConnection(ErrorResponse errorResponse)
			throws Http2Exception {
		// disconnect();
		throw new Http2Exception(Http2Error.CONNECT_ERROR,
				errorResponse.getReason());
	}
	
	public Http2Client(final X509Certificate certificate,
			final PrivateKey privateKey, final String privateKeyPassword,
			boolean sandboxEnvironment, int maxSession) throws SSLException {
		this(certificate, privateKey, privateKeyPassword, null,
				sandboxEnvironment, maxSession);
	}
	
	public Http2Client(final X509Certificate certificate,
			final PrivateKey privateKey, final String privateKeyPassword,
			final EventLoopGroup eventLoopGroup,
			final boolean sandboxEnvironment, int maxSession)
			throws SSLException {
		this(SslUtils.getSslContextWithCertificateAndPrivateKey(certificate,
				privateKey, privateKeyPassword), eventLoopGroup,
				sandboxEnvironment, maxSession);
	}
	
	protected Http2Client(final SslContext sslCtx, final EventLoopGroup group,
			final boolean sandboxEnvironment, int maxSession) {
		this.maxSession = maxSession;
		if (sandboxEnvironment) {
			this.host = HttpProperties.DEVELOPMENT_APNS_HOST;
			this.port = HttpProperties.DEFAULT_APNS_PORT;
		} else {
			this.host = HttpProperties.PRODUCTION_APNS_HOST;
			this.port = HttpProperties.DEFAULT_APNS_PORT;
		}
		
		this.bootstrap = new Bootstrap();
		
		if (null != group) {
			this.bootstrap.group(group);
		} else {
			this.bootstrap.group(this.group);
		}
		
		this.bootstrap.channel(NioSocketChannel.class);
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
				
				// 20s 写空闲就开始发送ping-pong心跳到服务端
				if (HttpProperties.DEFAULT_WRITE_TIMEOUT_MILLIS > 0) {
					pipeline.addLast(new WriteTimeoutHandler(
							HttpProperties.DEFAULT_WRITE_TIMEOUT_MILLIS,
							TimeUnit.MILLISECONDS));
				}
				
				pipeline.addLast(sslCtx.newHandler(channel.alloc()));
				pipeline.addLast(new ProtocolNegotiationHandlerImpl(
						ApplicationProtocolNames.HTTP_2));
				
				// the application layer
			}
			
		});
	}
	
	public final synchronized void start() {
		exec.submit(new Runnable() {
			
			@Override
			public void run() {
				doConnect();
			}
			
		});
	}
	
	public final synchronized void stop() {
		if (null != group) {
			group.shutdownGracefully();
		}
		
		if (null != sessionStore && !sessionStore.isEmpty()) {
			for (Channel channel : sessionStore) {
				channel.close();
			}
		}
	}
	
	private void doScheduleNextConnect() {
		if (null == lunchConnectFuture || lunchConnectFuture.isDone()) {
			lunchConnectFuture = exec.schedule(new Runnable() {
				
				@Override
				public void run() {
					doConnect();
				}
			}, reconnectTimeout, TimeUnit.SECONDS);
			
			LOG.trace("doScheduleNextConnect: next connect scheduled.");
		} else {
			LOG.trace("doScheduleNextConnect: next connect !NOT! scheduled.");
		}
	}
	
	private void doConnect() {
		exec.submit(new Runnable() {
			
			@Override
			public void run() {
				doScheduleNextConnect();
			}
			
		});
		
		// check session full
		if (sessionStore.size() >= maxSession) {
			LOG.trace("doConnect: reach max session: {}, cancel this action.",
					maxSession);
			return;
		}
		
		try {
			ChannelFuture future = bootstrap.connect(host, port).sync();
			future.addListener(new GenericFutureListener<ChannelFuture>() {
				
				@Override
				public void operationComplete(final ChannelFuture future)
						throws Exception {
					if (future.isSuccess()) {
						exec.submit(new Runnable() {
							
							@Override
							public void run() {
								onConnectComplete(future);
							}
							
						});
					} else {
						LOG.warn("APNs not connected. begin reconnect ...");
					}
				}
				
			});
			
			return;
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
	
	private final void onConnectComplete(final ChannelFuture future) {
		if (future.channel().isActive()) {
			final Channel channel = future.channel();
			LOG.info("onConnectComplete: session {} connected.", channel);
		} else {
			LOG.error("onConnectComplete: connect failed.");
		}
	}
	
	public final void addChannel(final Channel channel) {
		if (sessionStore.size() >= maxSession) {
			channel.close();
			LOG.info("not add channel=[{}] into sessionStore full.", channel);
			return;
		}
		
		sessionStore.add(channel);
		LOG.info("add channel=[{}] into sessionStore.", channel);
	}
	
	public final void removeChannel(final Channel channel) {
		sessionStore.remove(channel);
		LOG.info("remove channel=[{}] from sessionStore.", channel);
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
	
	public Channel next() {
		while (0 == sessionStore.size() && !this.stopped.get()) {
			try {
				Thread.sleep(1 * 1000);
			} catch (InterruptedException e) {
				LOG.error("send exception:", e);
			}
		}
		
		if (this.stopped.get()) {
			return null;
		}
		
		if (sessionStore.size() == 0) {
			return sessionStore.get(0);
		}
		
		int idx = sessionIdx.getAndIncrement();
		if (idx >= sessionStore.size()) {
			idx = 0;
			sessionIdx.set(idx);
		}
		
		return sessionStore.get(idx);
	}
	
	public void sendNotification(final PushNotification notification,
			final APNsCallBack callback) {
		// final long notificationId = this.nextId.getAndIncrement();
		
		verifyTopic(notification);
		
		final Channel channel = next();
		
		if (channel != null && channel.isActive() && channel.isWritable()) {
			
			// filter notification already request
			if (Http2Client.this.responsePromises.containsKey(notification)) {
				String fmt = name
						+ "-> The given notification has already been sent and not yet resolved.";
				
				final boolean success = false;
				final DefaultPushResponse response = new DefaultPushResponse(
						notification, success, fmt, null);
				callback.response(response);
			} else {
				Http2Client.this.responsePromises.put(notification, callback);
			}
			
			// write notification
			ChannelFuture writeFuture = channel.writeAndFlush(notification);
			writeFuture.addListener(new GenericFutureListener<ChannelFuture>() {
				
				@Override
				public void operationComplete(final ChannelFuture future)
						throws Exception {
					if (!future.isSuccess()) {
						LOG.debug("[{}] Failed to write notification: {}",
								name, notification, future.cause());
						
						Http2Client.this.responsePromises.remove(notification);
						
						final boolean success = false;
						final DefaultPushResponse response = new DefaultPushResponse(
								notification, success, future.cause()
										.getMessage(), null);
						
						// callback
						callback.response(response);
					} else {
						LOG.info("Successed to write push notification: {}",
								notification);
					}
				}
			});
			
		} else {
			// TODO send failed to processed
			LOG.error(
					"[{}] Failed to send push notification because client is not connected: {}",
					name, notification);
		}
		
		return;
	}
	
	void handleNotificationResponse(final PushResponse response) {
		LOG.debug("Received response from APNs gateway: {}", response);
		if (response.getNotification() != null) {
			final APNsCallBack callback = this.responsePromises.remove(response
					.getNotification());
			callback.response(response);
		} else {
			this.responsePromises.clear();
		}
		
	}
	
}
