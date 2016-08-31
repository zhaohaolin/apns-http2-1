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

import br.com.zup.push.data.PushNotification;

/**
 * TODO
 * 
 * @author joe.zhao
 * @version $Id: Http2ClientHandlerBuilder, v 0.1 2016年9月5日 上午8:53:01 Exp $
 */
public class Http2ClientHandlerBuilder<S extends PushNotification>
		extends
		AbstractHttp2ConnectionHandlerBuilder<Http2ClientHandler<S>, Http2ClientHandlerBuilder<S>> {
	
	private Http2Client<S>	http2Client;
	private String			authority;
	private int				maxUnflushedNotifications	= 0;
	
	public Http2ClientHandlerBuilder<S> http2Client(
			final Http2Client<S> pushHttpClient) {
		this.http2Client = pushHttpClient;
		return this;
	}
	
	public Http2Client<S> http2Client() {
		return this.http2Client;
	}
	
	public Http2ClientHandlerBuilder<S> authority(final String authority) {
		this.authority = authority;
		return this;
	}
	
	public String authority() {
		return this.authority;
	}
	
	public Http2ClientHandlerBuilder<S> maxUnflushedNotifications(
			final int maxUnflushedNotifications) {
		this.maxUnflushedNotifications = maxUnflushedNotifications;
		return this;
	}
	
	public int maxUnflushedNotifications() {
		return this.maxUnflushedNotifications;
	}
	
	@Override
	public Http2ClientHandlerBuilder<S> server(final boolean isServer) {
		return super.server(isServer);
	}
	
	@Override
	public Http2ClientHandlerBuilder<S> encoderEnforceMaxConcurrentStreams(
			final boolean enforceMaxConcurrentStreams) {
		return super
				.encoderEnforceMaxConcurrentStreams(enforceMaxConcurrentStreams);
	}
	
	@Override
	public Http2ClientHandler<S> build(final Http2ConnectionDecoder decoder,
			final Http2ConnectionEncoder encoder,
			final Http2Settings initialSettings) {
		Objects.requireNonNull(this.authority(),
				"Authority must be set before building an HttpClientHandler.");
		
		final Http2ClientHandler<S> handler = new Http2ClientHandler<S>(
				decoder, encoder, initialSettings, this.http2Client(),
				this.authority(), this.maxUnflushedNotifications());
		this.frameListener(handler.new ApnsClientHandlerFrameAdapter());
		return handler;
	}
	
	@Override
	public Http2ClientHandler<S> build() {
		return super.build();
	}
}
