package br.com.zup.push.client;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.WriteTimeoutException;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.PromiseCombiner;
import io.netty.util.concurrent.ScheduledFuture;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import br.com.zup.push.data.DefaultPushResponse;
import br.com.zup.push.data.PushNotification;
import br.com.zup.push.util.DateAsMillisecondsSinceEpochTypeAdapter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

class Http2ClientHandler<T extends PushNotification> extends
		Http2ConnectionHandler {
	
	private final AtomicBoolean					receivedInitialSettings			= new AtomicBoolean(
																						false);
	private long								nextStreamId					= 1;
	
	private final Map<Integer, T>				pushNotificationsByStreamId		= new HashMap<Integer, T>();
	private final Map<Integer, Http2Headers>	headersByStreamId				= new HashMap<Integer, Http2Headers>();
	
	private final Http2Client<T>				pushHttpClient;
	private final String						authority;
	
	private long								nextPingId						= new Random()
																						.nextLong();
	private ScheduledFuture<?>					pingTimeoutFuture;
	
	private final int							maxUnflushedNotifications;
	private int									unflushedNotifications			= 0;
	
	private static final int					PING_TIMEOUT_SECONDS			= 30;
	
	private static final String					APNS_PATH_PREFIX				= "/3/device/";
	private static final AsciiString			APNS_EXPIRATION_HEADER			= new AsciiString(
																						"apns-expiration");
	private static final AsciiString			APNS_TOPIC_HEADER				= new AsciiString(
																						"apns-topic");
	private static final AsciiString			APNS_PRIORITY_HEADER			= new AsciiString(
																						"apns-priority");
	
	private static final long					STREAM_ID_RESET_THRESHOLD		= Integer.MAX_VALUE - 1;
	private static final int					INITIAL_PAYLOAD_BUFFER_CAPACITY	= 4096;
	
	private static final Gson					GSON							= new GsonBuilder()
																						.registerTypeAdapter(
																								Date.class,
																								new DateAsMillisecondsSinceEpochTypeAdapter())
																						.create();
	
	private static final Logger					LOG								= LoggerFactory
																						.getLogger(Http2ClientHandler.class);
	
	protected class ApnsClientHandlerFrameAdapter extends Http2FrameAdapter {
		@Override
		public void onSettingsRead(final ChannelHandlerContext context,
				final Http2Settings settings) {
			LOG.trace("Received settings from APNs gateway: {}", settings);
			
			synchronized (Http2ClientHandler.this.receivedInitialSettings) {
				Http2ClientHandler.this.receivedInitialSettings.set(true);
				Http2ClientHandler.this.receivedInitialSettings.notifyAll();
			}
		}
		
		@Override
		public int onDataRead(final ChannelHandlerContext context,
				final int streamId, final ByteBuf data, final int padding,
				final boolean endOfStream) throws Http2Exception {
			LOG.trace("Received data from APNs gateway on stream {}: {}",
					streamId, data.toString(StandardCharsets.UTF_8));
			
			final int bytesProcessed = data.readableBytes() + padding;
			
			if (endOfStream) {
				final Http2Headers headers = Http2ClientHandler.this.headersByStreamId
						.remove(streamId);
				final T pushNotification = Http2ClientHandler.this.pushNotificationsByStreamId
						.remove(streamId);
				
				final boolean success = HttpResponseStatus.OK
						.equals(HttpResponseStatus.parseLine(headers.status()));
				final ErrorResponse errorResponse = GSON.fromJson(
						data.toString(StandardCharsets.UTF_8),
						ErrorResponse.class);
				
				Http2ClientHandler.this.pushHttpClient
						.handlePushNotificationResponse(new DefaultPushResponse<>(
								pushNotification, success, errorResponse
										.getReason(), errorResponse
										.getTimestamp()));
			} else {
				LOG.error("Gateway sent a DATA frame that was not the end of a stream.");
			}
			
			return bytesProcessed;
		}
		
		@Override
		public void onHeadersRead(final ChannelHandlerContext context,
				final int streamId, final Http2Headers headers,
				final int streamDependency, final short weight,
				final boolean exclusive, final int padding,
				final boolean endOfStream) throws Http2Exception {
			this.onHeadersRead(context, streamId, headers, padding, endOfStream);
		}
		
		@Override
		public void onHeadersRead(final ChannelHandlerContext context,
				final int streamId, final Http2Headers headers,
				final int padding, final boolean endOfStream)
				throws Http2Exception {
			LOG.trace("Received headers from APNs gateway on stream {}: {}",
					streamId, headers);
			
			final boolean success = HttpResponseStatus.OK
					.equals(HttpResponseStatus.parseLine(headers.status()));
			
			if (endOfStream) {
				if (!success) {
					LOG.error("Gateway sent an end-of-stream HEADERS frame for an unsuccessful notification.");
				}
				
				final T pushNotification = Http2ClientHandler.this.pushNotificationsByStreamId
						.remove(streamId);
				
				Http2ClientHandler.this.pushHttpClient
						.handlePushNotificationResponse(new DefaultPushResponse<>(
								pushNotification, success, null, null));
			} else {
				Http2ClientHandler.this.headersByStreamId
						.put(streamId, headers);
			}
		}
		
		@Override
		public void onPingAckRead(final ChannelHandlerContext context,
				final ByteBuf data) {
			if (Http2ClientHandler.this.pingTimeoutFuture != null) {
				LOG.trace("Received reply to ping.");
				Http2ClientHandler.this.pingTimeoutFuture.cancel(false);
			} else {
				LOG.error("Received PING ACK, but no corresponding outbound PING found.");
			}
		}
		
		@Override
		public void onGoAwayRead(final ChannelHandlerContext context,
				final int lastStreamId, final long errorCode,
				final ByteBuf debugData) throws Http2Exception {
			LOG.info("Received GOAWAY from APNs server: {}",
					debugData.toString(StandardCharsets.UTF_8));
			
			ErrorResponse errorResponse = GSON.fromJson(
					debugData.toString(StandardCharsets.UTF_8),
					ErrorResponse.class);
			Http2ClientHandler.this.pushHttpClient
					.abortConnection(errorResponse);
		}
	}
	
	protected Http2ClientHandler(final Http2ConnectionDecoder decoder,
			final Http2ConnectionEncoder encoder,
			final Http2Settings initialSettings,
			final Http2Client<T> pushHttpClient, final String authority,
			final int maxUnflushedNotifications) {
		super(decoder, encoder, initialSettings);
		
		this.pushHttpClient = pushHttpClient;
		this.authority = authority;
		this.maxUnflushedNotifications = maxUnflushedNotifications;
	}
	
	@Override
	public void write(final ChannelHandlerContext context,
			final Object message, final ChannelPromise writePromise)
			throws Http2Exception {
		try {
			final T notification = (T) message;
			
			final int streamId = (int) this.nextStreamId;
			
			final Http2Headers headers = new DefaultHttp2Headers()
					.method(HttpMethod.POST.asciiName())
					.authority(this.authority)
					.path(APNS_PATH_PREFIX + notification.getToken())
					.addInt(APNS_EXPIRATION_HEADER,
							notification.getExpiration() == null ? 0
									: (int) (notification.getExpiration()
											.getTime() / 1000));
			
			if (notification.getPriority() != null) {
				headers.addInt(APNS_PRIORITY_HEADER, notification.getPriority()
						.getCode());
			}
			
			if (notification.getTopic() != null) {
				headers.add(APNS_TOPIC_HEADER, notification.getTopic());
			}
			
			final ChannelPromise headersPromise = context.newPromise();
			this.encoder().writeHeaders(context, streamId, headers, 0, false,
					headersPromise);
			LOG.trace("Wrote headers on stream {}: {}", streamId, headers);
			
			final ByteBuf payloadBuffer = context.alloc().ioBuffer(
					INITIAL_PAYLOAD_BUFFER_CAPACITY);
			payloadBuffer.writeBytes(notification.getPayload().getBytes(
					StandardCharsets.UTF_8));
			
			final ChannelPromise dataPromise = context.newPromise();
			this.encoder().writeData(context, streamId, payloadBuffer, 0, true,
					dataPromise);
			LOG.trace("Wrote payload on stream {}: {}", streamId,
					notification.getPayload());
			
			final PromiseCombiner promiseCombiner = new PromiseCombiner();
			promiseCombiner.addAll(headersPromise, dataPromise);
			promiseCombiner.finish(writePromise);
			
			writePromise
					.addListener(new GenericFutureListener<ChannelPromise>() {
						
						@Override
						public void operationComplete(
								final ChannelPromise future) throws Exception {
							if (future.isSuccess()) {
								Http2ClientHandler.this.pushNotificationsByStreamId
										.put(streamId, notification);
							} else {
								LOG.trace(
										"Failed to write push notification on stream {}.",
										streamId, future.cause());
							}
						}
					});
			
			this.nextStreamId += 2;
			
			if (++this.unflushedNotifications >= this.maxUnflushedNotifications) {
				this.flush(context);
			}
			
			if (this.nextStreamId >= STREAM_ID_RESET_THRESHOLD) {
				context.close();
			}
			
		} catch (final ClassCastException e) {
			LOG.error("Unexpected object in pipeline: {}", message);
			context.write(message, writePromise);
		}
	}
	
	@Override
	public void flush(final ChannelHandlerContext context)
			throws Http2Exception {
		super.flush(context);
		
		this.unflushedNotifications = 0;
	}
	
	@Override
	public void userEventTriggered(final ChannelHandlerContext context,
			final Object event) throws Exception {
		if (event instanceof IdleStateEvent) {
			final IdleStateEvent idleStateEvent = (IdleStateEvent) event;
			
			if (IdleState.WRITER_IDLE.equals(idleStateEvent.state())) {
				if (this.unflushedNotifications > 0) {
					this.flush(context);
				}
			} else {
				assert PING_TIMEOUT_SECONDS < HttpProperties.PING_IDLE_TIME_MILLIS;
				
				LOG.trace("Sending ping due to inactivity.");
				
				final ByteBuf pingDataBuffer = context.alloc().ioBuffer(8, 8);
				pingDataBuffer.writeLong(this.nextPingId++);
				
				this.encoder()
						.writePing(context, false, pingDataBuffer,
								context.newPromise())
						.addListener(
								new GenericFutureListener<ChannelFuture>() {
									
									@Override
									public void operationComplete(
											final ChannelFuture future)
											throws Exception {
										if (future.isSuccess()) {
											Http2ClientHandler.this.pingTimeoutFuture = future
													.channel().eventLoop()
													.schedule(
															new Runnable() {
																
																@Override
																public void run() {
																	LOG.debug("Closing channel due to ping timeout.");
																	future.channel()
																			.close();
																}
															},
															PING_TIMEOUT_SECONDS,
															TimeUnit.SECONDS);
										} else {
											LOG.debug(
													"Failed to write PING frame.",
													future.cause());
											future.channel().close();
										}
									}
								});
				
				this.flush(context);
			}
		}
		
		super.userEventTriggered(context, event);
	}
	
	@Override
	public void exceptionCaught(final ChannelHandlerContext context,
			final Throwable cause) throws Exception {
		if (cause instanceof WriteTimeoutException) {
			LOG.debug("Closing connection due to write timeout.");
			context.close();
		} else {
			LOG.warn("APNs client pipeline exception.", cause);
		}
	}
	
	void waitForInitialSettings() throws InterruptedException {
		synchronized (this.receivedInitialSettings) {
			while (!this.receivedInitialSettings.get()) {
				this.receivedInitialSettings.wait();
			}
		}
	}
}