/*
 * CopyRight (c) 2005-2012 Ezviz Co, Ltd. All rights reserved. Filename:
 * Http2ClientHandlerBuilder.java Creator: joe.zhao Create-Date: 上午8:53:01
 */
package br.com.zup.push.client;

import io.netty.handler.codec.http2.AbstractHttp2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Settings;

import java.util.Objects;

/**
 * TODO
 * 
 * @author joe.zhao
 * @version $Id: Http2ClientHandlerBuilder, v 0.1 2016年9月5日 上午8:53:01 Exp $
 */
public class Http2ClientHandlerBuilder
		extends
		AbstractHttp2ConnectionHandlerBuilder<Http2ClientHandler, Http2ClientHandlerBuilder> {
	
	private Http2Client	http2Client;
	private String		authority;
	private int			maxUnflushedNotifications	= 0;
	
	public Http2ClientHandlerBuilder http2Client(
			final Http2Client pushHttpClient) {
		this.http2Client = pushHttpClient;
		return this;
	}
	
	public Http2Client http2Client() {
		return this.http2Client;
	}
	
	public Http2ClientHandlerBuilder authority(final String authority) {
		this.authority = authority;
		return this;
	}
	
	public String authority() {
		return this.authority;
	}
	
	public Http2ClientHandlerBuilder maxUnflushedNotifications(
			final int maxUnflushedNotifications) {
		this.maxUnflushedNotifications = maxUnflushedNotifications;
		return this;
	}
	
	public int maxUnflushedNotifications() {
		return this.maxUnflushedNotifications;
	}
	
	@Override
	public Http2ClientHandlerBuilder server(final boolean isServer) {
		return super.server(isServer);
	}
	
	@Override
	public Http2ClientHandlerBuilder encoderEnforceMaxConcurrentStreams(
			final boolean enforceMaxConcurrentStreams) {
		return super
				.encoderEnforceMaxConcurrentStreams(enforceMaxConcurrentStreams);
	}
	
	@Override
	public Http2ClientHandler build(final Http2ConnectionDecoder decoder,
			final Http2ConnectionEncoder encoder,
			final Http2Settings initialSettings) {
		Objects.requireNonNull(this.authority(),
				"Authority must be set before building an HttpClientHandler.");
		
		final Http2ClientHandler handler = new Http2ClientHandler(decoder,
				encoder, initialSettings, this.http2Client(), this.authority(),
				this.maxUnflushedNotifications());
		this.frameListener(handler.new APNsClientHandlerFrameAdapter());
		return handler;
	}
	
	@Override
	public Http2ClientHandler build() {
		return super.build();
	}
}
