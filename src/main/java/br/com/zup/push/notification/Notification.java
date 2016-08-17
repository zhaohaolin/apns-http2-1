/*
 * Copyright (c) 2016, CleverTap All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * - Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 * 
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * 
 * - Neither the name of CleverTap nor the names of its contributors may be used
 * to endorse or promote products derived from this software without specific
 * prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package br.com.zup.push.notification;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * An entity containing the payload and the token.
 */
public class Notification {
	private final String	payload;
	private final String	token;
	private final String	topic;
	
	/**
	 * Constructs a new Notification with a payload and token.
	 *
	 * @param payload The JSON body (which is used for the request)
	 * @param token The device token
	 * @param topic The topic for this notification
	 */
	protected Notification(String payload, String token, String topic) {
		this.payload = payload;
		this.token = token;
		this.topic = topic;
	}
	
	/**
	 * Retrieves the topic.
	 *
	 * @return The topic
	 */
	public String getTopic() {
		return topic;
	}
	
	/**
	 * Retrieves the payload.
	 *
	 * @return The payload
	 */
	public String getPayload() {
		return payload;
	}
	
	/**
	 * Retrieves the token.
	 *
	 * @return The device token
	 */
	public String getToken() {
		return token;
	}
	
	private final static Gson	GSON	= new GsonBuilder().create();
	
	/**
	 * Builds a notification to be sent to APNS.
	 */
	public static class Builder {
		
		private final HashMap<String, Object>	root, aps;		// alert;
		private final String					token;
		private String							alert;
		private String							topic	= null;
		
		/**
		 * Creates a new notification builder.
		 *
		 * @param token The device token
		 */
		public Builder(String token) {
			this.token = token;
			root = new HashMap<>();
			aps = new HashMap<>();
			// alert = new HashMap<>();
		}
		
		// public Builder alertBody(String body) {
		// alert.put("body", body);
		// return this;
		// }
		//
		// public Builder alertTitle(String title) {
		// alert.put("title", title);
		// return this;
		// }
		
		public Builder alert(String alert) {
			// alert.put("title", title);
			this.alert = alert;
			return this;
		}
		
		public Builder sound(String sound) {
			if (sound != null) {
				aps.put("sound", sound);
			} else {
				aps.remove("sound");
			}
			
			return this;
		}
		
		public Builder category(String category) {
			if (category != null) {
				aps.put("category", category);
			} else {
				aps.remove("category");
			}
			return this;
		}
		
		public Builder badge(int badge) {
			aps.put("badge", badge);
			return this;
		}
		
		public Builder customField(String key, Object value) {
			root.put(key, value);
			return this;
		}
		
		public Builder topic(String topic) {
			this.topic = topic;
			return this;
		}
		
		public int size() {
			try {
				return build().getPayload().getBytes("UTF-8").length;
			} catch (UnsupportedEncodingException e) {
				throw new RuntimeException(e);
			}
		}
		
		/**
		 * Builds the notification. Also see
		 * {@link AsyncApnsClient#push(Notification, NotificationResponseListener)}
		 *
		 * @return The notification
		 */
		public Notification build() {
			root.put("aps", aps);
			aps.put("alert", alert);
			final String payload;
			try {
				payload = GSON.toJson(root);
			} catch (Exception e) {
				// Should not happen
				throw new RuntimeException(e);
			}
			return new Notification(payload, token, topic);
		}
	}
}
