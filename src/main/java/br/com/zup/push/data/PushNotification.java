package br.com.zup.push.data;

import br.com.zup.push.client.DeliveryPriority;

import java.util.Date;

public interface PushNotification {
	
	String getToken();
	
	String getPayload();
	
	Date getExpiration();
	
	DeliveryPriority getPriority();
	
	String getTopic();
	
	void setTopic(String topic);
	
}
