package br.com.zup.push.data;

import java.util.Date;

public interface PushNotificationResponse<T extends HttpPushNotification> {

    T getPushNotification();

    boolean isAccepted();

    String getRejectionReason();

    Date getTokenInvalidationTimestamp();
}
