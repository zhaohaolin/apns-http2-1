package br.com.zup.push.data;

import java.util.Date;

public interface PushResponse<T extends PushNotification> {
	
	T getPushNotification();
	
	boolean isAccepted();
	
	String getRejectionReason();
	
	Date getTokenInvalidationTimestamp();
}
