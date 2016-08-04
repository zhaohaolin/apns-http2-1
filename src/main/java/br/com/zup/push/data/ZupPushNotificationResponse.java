package br.com.zup.push.data;

import java.util.Date;

public class ZupPushNotificationResponse<T extends HttpPushNotification> implements PushNotificationResponse<T> {
    private final T pushNotification;
    private final boolean success;
    private final String rejectionReason;
    private final Date tokenExpirationTimestamp;

    public ZupPushNotificationResponse(final T pushNotification, final boolean success, final String rejectionReason, final Date tokenExpirationTimestamp) {
        this.pushNotification = pushNotification;
        this.success = success;
        this.rejectionReason = rejectionReason;
        this.tokenExpirationTimestamp = tokenExpirationTimestamp;
    }

    @Override
    public T getPushNotification() {
        return this.pushNotification;
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
        builder.append("ZupPushNotificationResponse [pushNotification=");
        builder.append(this.pushNotification);
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
