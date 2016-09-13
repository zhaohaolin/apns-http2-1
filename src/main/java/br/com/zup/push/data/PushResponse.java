package br.com.zup.push.data;

import java.util.Date;

public interface PushResponse {
	
	PushNotification getNotification();
	
	boolean isAccepted();
	
	String getRejectionReason();
	
	Date getTokenInvalidationTimestamp();
}
