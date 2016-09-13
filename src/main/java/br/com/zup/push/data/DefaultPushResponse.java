package br.com.zup.push.data;

import java.util.Date;

public class DefaultPushResponse implements PushResponse {
	private final PushNotification	notification;
	private final boolean			success;
	private final String			rejectionReason;
	private final Date				tokenExpirationTimestamp;
	
	public DefaultPushResponse(final PushNotification notification,
			final boolean success, final String rejectionReason,
			final Date tokenExpirationTimestamp) {
		this.notification = notification;
		this.success = success;
		this.rejectionReason = rejectionReason;
		this.tokenExpirationTimestamp = tokenExpirationTimestamp;
	}
	
	@Override
	public PushNotification getNotification() {
		return this.notification;
	}
	
	@Override
	public boolean isAccepted() {
		return this.success;
	}
	
	@Override
	public String getRejectionReason() {
		return this.rejectionReason;
	}
	
	@Override
	public Date getTokenInvalidationTimestamp() {
		return this.tokenExpirationTimestamp;
	}
	
	@Override
	public String toString() {
		final StringBuilder builder = new StringBuilder();
		builder.append("DefaultPushResponse [pushNotification=");
		builder.append(this.notification);
		builder.append(", success=");
		builder.append(this.success);
		builder.append(", rejectionReason=");
		builder.append(this.rejectionReason);
		builder.append(", tokenExpirationTimestamp=");
		builder.append(this.tokenExpirationTimestamp);
		builder.append("]");
		return builder.toString();
	}
}
